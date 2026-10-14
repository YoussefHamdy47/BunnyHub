package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import org.bunnys.bunnynexus.freebies.FreebieLifecycle.Cancel;
import org.bunnys.bunnynexus.freebies.FreebieLifecycle.Kind;
import org.bunnys.bunnynexus.freebies.FreebieLifecycle.Skip;
import org.bunnys.bunnynexus.freebies.FreebieLifecycleStore.Job;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Runs ended-card edits and ending-soon reminders on the freebie sender thread, inside the alert sender's
 * in-flight budget. {@link FreebieSender} only calls {@link #tick} after it found no alert due, and this worker
 * never holds more than half the budget, so a new alert's first post always finds free slots. The global pause
 * applies because the sender returns before calling here. Database work and outcomes run on the sender thread,
 * never on JDA callback threads.
 */
final class FreebieLifecycleWorker {
    static final int MAX_IN_FLIGHT = FreebieSender.MAX_IN_FLIGHT / 2;
    private static final Duration LEASE = Duration.ofMinutes(3), TIMEOUT = Duration.ofSeconds(45);

    private final FreebieRepository repository;
    private final FreebieSubscriptions subscriptions;
    private final Executor executor;
    private final Clock clock;
    private int own; // Sender thread only.
    private volatile boolean closed;

    FreebieLifecycleWorker(FreebieRepository repository, FreebieSubscriptions subscriptions, Executor executor, Clock clock) {
        this.repository = repository; this.subscriptions = subscriptions;
        this.executor = executor; this.clock = clock;
    }

    void close() { closed = true; }
    private FreebieLifecycleStore jobs() { return repository.lifecycle(); }
    int inFlight() { return own; }

    void tick(JDA client, AtomicInteger shared) { tick(client, shared, new AtomicInteger()); }

    /**
     * @param shared     the alert sender's in-flight counter; every slot taken here counts against it
     * @param background slots held by all lower-priority work (catch-up, DMs, this worker), capped together
     */
    /** Everything due, within the caps; the sender instead calls {@link #prepare} and {@link #claimOne}. */
    void tick(JDA client, AtomicInteger shared, AtomicInteger background) {
        prepare();
        while (claimOne(client, shared, background)) { /* Until a cap is reached or nothing is due. */ }
    }

    /** Once per sender tick: expired leases go back to the queue. */
    void prepare() { if (!closed) jobs().recover(clock.instant()); }

    /**
     * Claims and starts at most one job when this worker, the background budget and the alert budget all have room.
     * @return true when a job was claimed (whatever its outcome), so the caller may offer the next slot
     */
    boolean claimOne(JDA client, AtomicInteger shared, AtomicInteger background) {
        if (closed || own >= MAX_IN_FLIGHT || shared.get() >= FreebieSender.MAX_IN_FLIGHT
                || background.get() >= FreebieSender.BACKGROUND_SLOTS) return false;
        var claimed = jobs().claim(clock.instant(), LEASE);
        if (claimed.isEmpty()) return false;
        own++; shared.incrementAndGet(); background.incrementAndGet();
        Runnable release = () -> { own--; shared.decrementAndGet(); background.decrementAndGet(); };
        boolean handedOff = false;
        try { handedOff = start(client, claimed.get(), release); }
        catch (RuntimeException failure) { log(failure); } // The lease expires and the job is recovered.
        finally { if (!handedOff) release.run(); }
        return true;
    }

    /** @return true when a Discord request is in flight and its completion will run {@code release} */
    private boolean start(JDA client, Job job, Runnable release) {
        Instant now = clock.instant();
        if (job.attempts() > FreebieLifecycle.MAX_ATTEMPTS) { jobs().failed(job, "attempts exhausted", now); return false; }
        if (job.recipientId() != null) return startPrivateEdit(client, job, release);
        FreebieOffer offer = repository.offer(job.offerId()).orElse(null);
        if (job.kind() == Kind.REMIND) {
            var cancel = reminderCancel(job, offer, now);
            if (cancel.isPresent()) { jobs().cancelled(job, cancel.get(), now); return false; }
        }
        long guildId = Long.parseLong(job.guildId());
        if (client.isUnavailable(guildId)) { jobs().release(job, now.plus(Duration.ofMinutes(2)), now); return false; }
        Guild guild = client.getGuildById(guildId);
        if (guild == null) { jobs().skipped(job, Skip.BOT_REMOVED, now); return false; }
        var channel = guild.getChannelById(StandardGuildMessageChannel.class, job.channelId());
        if (channel == null) { jobs().skipped(job, Skip.UNKNOWN_CHANNEL, now); return false; }
        var self = guild.getSelfMember();
        String selfId = client.getSelfUser().getId();
        CompletableFuture<String> work;
        if (job.kind() == Kind.EDIT) {
            // Reading the card back needs history; editing its own message needs no send permission.
            if (!self.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)) {
                jobs().skipped(job, Skip.MISSING_ACCESS, now); return false;
            }
            String reference = FreebieMessages.reference(job.deliveryId());
            try {
                work = channel.retrieveMessageById(job.messageId()).timeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).submit()
                        .thenComposeAsync(message -> edit(job, message, selfId, reference), executor);
            } catch (RuntimeException failure) { work = CompletableFuture.failedFuture(failure); }
        } else {
            if (!self.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)) {
                jobs().skipped(job, Skip.MISSING_ACCESS, now); return false;
            }
            if (job.verifyFirst() && !self.hasPermission(channel, Permission.MESSAGE_HISTORY)) {
                jobs().skipped(job, Skip.HISTORY_UNAVAILABLE, now); return false;
            }
            CompletableFuture<Optional<Message>> existing;
            try {
                existing = job.verifyFirst()
                        ? channel.getHistory().retrievePast(50).timeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).submit()
                            .thenApply(messages -> messages.stream().filter(m -> m.getAuthor().getId().equals(selfId))
                                    .filter(m -> FreebieLifecycleMessages.carriesReminderReference(m, job.offerId(), job.channelId())).findFirst())
                        : CompletableFuture.completedFuture(Optional.empty());
            } catch (RuntimeException failure) { existing = CompletableFuture.failedFuture(failure); }
            work = existing.thenComposeAsync(found -> found.isPresent()
                    ? CompletableFuture.completedFuture(found.get().getId()) : remind(job, guild, channel), executor);
        }
        complete(work, job, release);
        return true;
    }

    private boolean startPrivateEdit(JDA client, Job job, Runnable release) {
        if (job.kind() != Kind.EDIT) {
            jobs().skipped(job, Skip.NOT_OURS, clock.instant());
            return false;
        }
        CompletableFuture<String> work;
        try {
            String selfId = client.getSelfUser().getId();
            String reference = FreebieMessages.reference(job.deliveryId());
            work = client.openPrivateChannelById(job.recipientId()).timeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).submit()
                    .thenComposeAsync(channel -> {
                        if (!channel.getId().equals(job.channelId()))
                            return CompletableFuture.failedFuture(new Skipped(Skip.UNKNOWN_CHANNEL));
                        return channel.retrieveMessageById(job.messageId()).timeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).submit()
                                .thenComposeAsync(message -> edit(job, message, selfId, reference), executor);
                    }, executor);
        } catch (RuntimeException failure) { work = CompletableFuture.failedFuture(failure); }
        complete(work, job, release);
        return true;
    }

    private void complete(CompletableFuture<String> work, Job job, Runnable release) {
        work.whenComplete((messageId, error) -> dispatch(() -> {
            try { outcome(job, messageId, error); }
            catch (RuntimeException storage) { log(storage); }
            finally { release.run(); }
        }, release));
    }

    private CompletableFuture<String> edit(Job job, Message message, String selfId, String reference) {
        if (!message.getAuthor().getId().equals(selfId) || !FreebieMessages.carriesReference(message, reference))
            return CompletableFuture.failedFuture(new Skipped(Skip.NOT_OURS));
        if (FreebieLifecycleMessages.isEnded(message, reference)) return CompletableFuture.completedFuture(message.getId());
        if (closed || !jobs().owns(job, clock.instant())) return CompletableFuture.failedFuture(new LeaseLost());
        return message.editMessage(FreebieLifecycleMessages.ended(message.getEmbeds(), message.getComponents(), reference, job.withdrawn()))
                .timeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).submit().thenApply(Message::getId);
    }

    private CompletableFuture<String> remind(Job job, Guild guild, StandardGuildMessageChannel channel) {
        Instant now = clock.instant();
        // Re-check right before posting: the offer may have ended or the setting changed since the claim.
        var offer = repository.offer(job.offerId()).orElse(null);
        var cancel = reminderCancel(job, offer, now);
        if (cancel.isPresent()) return CompletableFuture.failedFuture(new Cancelled(cancel.get()));
        if (closed || !jobs().submitting(job, now)) return CompletableFuture.failedFuture(new LeaseLost());
        Optional<String> role = subscriptions.find(job.guildId(), job.channelId(), offer.store())
                .flatMap(FreebieSubscriptions.Subscription::roleId).filter(id -> guild.getRoleById(id) != null);
        return channel.sendMessage(FreebieLifecycleMessages.reminder(offer, job.channelId(), role, now))
                .setMessageReference(job.messageId()).failOnInvalidReply(false)
                .setNonce(FreebieLifecycleMessages.reminderNonce(job.offerId(), job.channelId()))
                .timeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).submit().thenApply(Message::getId);
    }

    /** Why a reminder must not post now, if any: offer no longer live, setting off, or too close to the end. */
    private Optional<Cancel> reminderCancel(Job job, FreebieOffer offer, Instant now) {
        if (!FreebieLifecycle.live(offer, now)) return Optional.of(Cancel.OFFER_ENDED);
        var setting = subscriptions.find(job.guildId(), job.channelId(), offer.store());
        if (setting.isEmpty() || !FreebieLifecycle.REMINDER_HOURS.contains(setting.get().reminderHours())) return Optional.of(Cancel.SETTING_OFF);
        if (Duration.between(now, offer.realEnd().orElseThrow()).compareTo(FreebieLifecycle.MIN_LEFT) < 0) return Optional.of(Cancel.TOO_LATE);
        return Optional.empty();
    }

    private void outcome(Job job, String messageId, Throwable error) {
        Instant now = clock.instant();
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause == null) { jobs().done(job, messageId, now); return; }
        if (cause instanceof LeaseLost) return;
        if (cause instanceof Skipped skipped) { jobs().skipped(job, skipped.reason, now); return; }
        if (cause instanceof Cancelled cancelled) { jobs().cancelled(job, cancelled.reason, now); return; }
        var verdict = FreebieLifecycle.classify(cause);
        String detail = detail(cause);
        switch (verdict.outcome()) {
            case SKIP -> jobs().skipped(job, verdict.skip(), now);
            case FAIL -> jobs().failed(job, detail, now);
            case RETRY -> {
                // A reminder that may have been submitted is uncertain: the next attempt checks history first.
                var delay = FreebieLifecycle.retryDelay(job.attempts());
                if (delay.isEmpty()) jobs().failed(job, detail, now);
                else jobs().retry(job, now.plus(delay.get()), job.verifyFirst() || job.kind() == Kind.REMIND, detail, now);
            }
        }
    }

    /** Stored detail; never lets a malformed throwable stop the outcome from being recorded. */
    private static String detail(Throwable cause) {
        try { return FailureDiagnostics.describe(cause); }
        catch (RuntimeException unreadable) { return cause.getClass().getSimpleName(); }
    }

    private void dispatch(Runnable action, Runnable release) {
        try { executor.execute(action); }
        catch (RejectedExecutionException rejected) { release.run(); } // Lease remains recoverable after shutdown.
    }

    private static void log(RuntimeException failure) {
        BunnyLog.error("[Freebies] Card update or reminder will recover | " + FailureDiagnostics.describe(failure));
    }

    /** Control-flow outcomes decided before or instead of a Discord request. */
    private static final class Skipped extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;
        final Skip reason;
        Skipped(Skip reason) { super(reason.name(), null, false, false); this.reason = reason; }
    }
    private static final class Cancelled extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;
        final Cancel reason;
        Cancelled(Cancel reason) { super(reason.name(), null, false, false); this.reason = reason; }
    }
    private static final class LeaseLost extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;
        LeaseLost() { super("lease lost", null, false, false); }
    }
}
