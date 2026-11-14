package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Sends approved alerts, one durable delivery per channel. Runs on its own single thread; Discord calls are
 * asynchronous and their results are handled back on that thread, never on JDA callback threads.
 *
 * <p>Retry safety: every delivery uses a fixed nonce (Discord drops a repeat within a few minutes) and
 * after an uncertain failure the next attempt first searches the channel for the message's footer
 * reference, so a timeout that actually delivered is recorded as sent instead of being posted twice.
 */
final class FreebieSender {
    static final int MAX_IN_FLIGHT = 8;
    /**
     * Catch-up alerts, personal DMs and card edits/reminders together never hold more than this, so a newly
     * approved game's first posts always find free slots.
     */
    static final int BACKGROUND_SLOTS = MAX_IN_FLIGHT / 2;
    private static final Duration LEASE = Duration.ofMinutes(3), SEND_TIMEOUT = Duration.ofSeconds(45);
    /** The wait after a tick that found work, and the waits after each further tick in a row that found none. */
    static final Duration BUSY_DELAY = Duration.ofSeconds(1);
    static final List<Duration> IDLE_DELAYS = List.of(Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(30));

    private final FreebieRepository repository;
    private final Supplier<JDA> jda;
    private final ScheduledExecutorService executor;
    private final Clock clock;
    private final FreebieNotifier notifier;
    private final AtomicInteger inFlight = new AtomicInteger(), background = new AtomicInteger();
    private FreebieSubscriptions subscriptions;
    private FreebieLifecycleWorker lifecycle;
    private FreebieDmWorker dms;
    private FreebieAnnouncementSender announcements;
    private FreebieAnnouncementWorker pages;
    private FreebiePublisher publisher;
    private FreebieBroadcastWorker broadcasts;
    private final FreebieIdleBackoff pacing;
    /** Set by {@link #wake()}: the next tick also plans publications for pages sent since the last one. */
    private final AtomicBoolean planPublications = new AtomicBoolean(true);
    private boolean foundWork; // Sender thread only: the previous tick's result.
    private volatile boolean closed;

    FreebieSender(FreebieRepository repository, Supplier<JDA> jda, ScheduledExecutorService executor, Clock clock, FreebieNotifier notifier) {
        this.repository = repository; this.jda = jda; this.executor = executor; this.clock = clock; this.notifier = notifier;
        pacing = new FreebieIdleBackoff(executor, "sender", BUSY_DELAY, IDLE_DELAYS, this::tick);
    }

    /** Starts the self-rescheduling tick on the sender thread. */
    void start(Duration initialDelay) { pacing.start(initialDelay); }

    /**
     * Work became due now (an approval, a catch-up, a retry, a resume): one tick at once, then every second again
     * until the queues are idle. Safe from any thread, including JDA callbacks; it only schedules. Several calls
     * before that tick share it.
     */
    void wake() {
        planPublications.set(true);
        pacing.wake();
    }

    FreebieSender withSubscriptions(FreebieSubscriptions value) { subscriptions = value; return this; }
    FreebieSender withLifecycle(FreebieLifecycleWorker value) { lifecycle = value; return this; }
    FreebieSender withDms(FreebieDmWorker value) { dms = value; return this; }
    FreebieSender withBroadcasts(FreebieBroadcastWorker value) { broadcasts = value; return this; }

    private FreebiePings pings;
    /** The per-channel ping budget for per-game alerts (the rollback path); without one every alert that may ping does. */
    FreebieSender withPings(FreebiePings value) { pings = value; return this; }

    private boolean pingAllowed(FreebieRepository.Claimed job, Instant now) {
        if (pings == null) return true;
        var take = pings.take(job.guildId(), job.channelId(), job.id(), now);
        if (!take.allowed())
            BunnyLog.info("[Freebies] No ping for " + job.id() + ": " + FreebiePings.since(take, now) + ".");
        return take.allowed();
    }
    FreebieSender withAnnouncements(FreebieAnnouncementSender sender, FreebieAnnouncementWorker worker, FreebiePublisher publishing) {
        announcements = sender; pages = worker; publisher = publishing; return this;
    }
    int background() { return background.get(); }

    int inFlight() { return inFlight.get(); }
    void close() { closed = true; pacing.close(); }

    /**
     * One pass over the queues, on the sender thread. Expired leases are recovered by housekeeping, not here.
     *
     * @return true when the next tick should come after {@link #BUSY_DELAY}: something was claimed or is still in
     * flight, or this tick read nothing (disconnected or paused), so the short interval is free and sending resumes
     * at once. False after an idle pass, which backs off through {@link #IDLE_DELAYS}.
     */
    boolean tick() {
        if (closed) return false;
        JDA client = jda.get();
        if (client == null || client.getStatus() != JDA.Status.CONNECTED) return true;
        // Global kill switch, from memory: queued work waits, nothing is lost. Housekeeping re-reads the database
        // value, and the database is read again before every REST submission (eligible()).
        if (repository.sendingPausedCached()) return true;
        boolean plan = planPublications.getAndSet(false) || foundWork;
        foundWork = pass(client, plan) || inFlight.get() > 0;
        return foundWork;
    }

    /** @return whether anything was claimed */
    private boolean pass(JDA client, boolean planPages) {
        boolean drained = false, claimedAny = false;
        while (!closed && inFlight.get() < MAX_IN_FLIGHT) {
            var claimed = repository.claim(clock.instant(), LEASE);
            if (claimed.isEmpty()) { drained = true; break; }
            claimedAny = true;
            launch(client, claimed.get());
        }
        if (!drained) return claimedAny;
        // Lower priority, only in slots no due first post is waiting for and together within BACKGROUND_SLOTS.
        // Time-sensitive work first: reminders by due time, then ended-card edits (one queue, see
        // FreebieLifecycleStore.claim). Then catch-up alerts and personal DMs take turns, so neither backlog
        // starves the other. Reminders are offered first on every tick, so a backlog only delays one until a
        // slot frees up (at most one request timeout), never indefinitely.
        while (lifecycle != null && lifecycle.claimOne(client, inFlight, background)) claimedAny = true; // Due card work.
        // Announcement edits (ended/withdrawn games, corrections, silent joins), then automatic publishing.
        while (pages != null && pages.claimOne(client, inFlight, background)) claimedAny = true; // Due page edits.
        // Pages are sent on this thread, so a tick after a wake or after a tick with work finds the new ones;
        // housekeeping plans any other.
        if (publisher != null && planPages) publisher.plan();
        while (publisher != null && publisher.claimOne(client, inFlight, background)) claimedAny = true; // Due publications.
        boolean more = true;
        while (more && !closed) {
            boolean catchUp = claimCatchUpOne(client);
            boolean dm = dms != null && dms.claimOne(client, inFlight, background);
            // Owner announcements take turns with catch-up and DMs, after every first post and card update.
            boolean announcement = broadcasts != null && broadcasts.claimOne(client, inFlight, background);
            more = catchUp || dm || announcement;
            claimedAny |= more;
        }
        return claimedAny;
    }

    private boolean claimCatchUpOne(JDA client) {
        if (closed || inFlight.get() >= MAX_IN_FLIGHT || background.get() >= BACKGROUND_SLOTS) return false;
        var claimed = repository.claimCatchUp(clock.instant(), LEASE);
        if (claimed.isEmpty()) return false;
        launch(client, claimed.get());
        return true;
    }

    private void launch(JDA client, FreebieRepository.Claimed job) {
        inFlight.incrementAndGet();
        if (job.catchUp()) background.incrementAndGet();
        // Exactly one release per claim: here for synchronous outcomes, in the callback otherwise.
        boolean handedOff = false;
        try { handedOff = start(client, job); }
        finally { if (!handedOff) done(job); }
    }

    private void done(FreebieRepository.Claimed job) {
        inFlight.decrementAndGet();
        if (job.catchUp()) background.decrementAndGet();
    }

    /** @return true when a Discord request is in flight and its callback will call {@link #done} */
    private boolean start(JDA client, FreebieRepository.Claimed job) {
        Instant now = clock.instant();
        FreebieOffer offer = repository.offer(job.offerId()).orElse(null);
        // COMPLETED still allows catch-up sends for late subscribers; STOPPED/REJECTED/EXPIRED never send.
        if (offer == null || (offer.state() != FreebieOffer.State.APPROVED && offer.state() != FreebieOffer.State.COMPLETED)) {
            repository.markCancelled(job, "offer no longer approved", now);
            return false;
        }
        if (offer.ended(now)) {
            repository.markCancelled(job, "offer ended", now);
            return false;
        }
        if (client.isUnavailable(Long.parseLong(job.guildId()))) {
            // Discord outage for this guild: not the server's fault and not an attempt.
            repository.release(job, now.plus(Duration.ofMinutes(2)), now);
            return false;
        }
        Guild guild = client.getGuildById(job.guildId());
        if (guild == null) return failed(job, offer, FreebieFailure.BOT_REMOVED, "guild not in cache");
        var channel = guild.getChannelById(StandardGuildMessageChannel.class, job.channelId());
        if (channel == null && guild.getGuildChannelById(job.channelId()) == null) {
            // Connected, loaded and available guild without the channel: it was deleted. End it so the summary can finish.
            repository.deliveries().cancelDeleted(job, now);
            return false;
        }
        if (channel == null) return failed(job, offer, FreebieFailure.CHANNEL_MISSING, "channel not found");
        var self = guild.getSelfMember();
        if (!self.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS))
            return failed(job, offer, FreebieFailure.MISSING_PERMISSIONS, "missing view/send/embed");
        // A deleted role must not block the alert; it just goes out without a ping.
        // Approved with "Send without ping": the alert still posts, without the role mention.
        Optional<String> role = repository.quiet(job.offerId()) ? Optional.empty() : job.roleId().filter(id -> guild.getRoleById(id) != null);
        // Announcement rows follow their page; the page is the send unit (one message, one ping).
        if (job.announcementKey().isPresent() && announcements != null)
            return announcements.start(client, job, guild, channel, role, () -> done(job));
        String reference = FreebieMessages.reference(job.id());

        if (job.verifyFirst() && !self.hasPermission(channel, Permission.MESSAGE_HISTORY)) {
            repository.deliveries().block(job, FreebieDeliveryStore.Block.HISTORY_PERMISSION, now);
            return false;
        }
        CompletableFuture<Optional<Message>> existing;
        try {
            existing = job.verifyFirst()
                    ? channel.getHistory().retrievePast(50).timeout(SEND_TIMEOUT.toSeconds(), TimeUnit.SECONDS).submit()
                        .thenApply(messages -> messages.stream()
                                .filter(m -> m.getAuthor().getId().equals(client.getSelfUser().getId()))
                                .filter(m -> FreebieMessages.carriesReference(m, reference)).findFirst())
                    : CompletableFuture.completedFuture(Optional.empty());
        } catch (RuntimeException failure) {
            repository.deliveries().block(job, FreebieDeliveryStore.Block.HISTORY_FAILED, now);
            return false;
        }
        existing.whenComplete((found, error) -> dispatch(job, () -> {
            boolean submitted = false;
            try {
                if (error != null) {
                    repository.deliveries().block(job, FreebieDeliveryStore.Block.HISTORY_FAILED, clock.instant());
                } else if (found.isPresent()) {
                    repository.markSent(job, found.get().getId(), clock.instant());
                } else if (eligible(job)) {
                    if (!self.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)
                            || (job.verifyFirst() && !self.hasPermission(channel, Permission.MESSAGE_HISTORY))) {
                        repository.deliveries().block(job, FreebieDeliveryStore.Block.DESTINATION, clock.instant());
                    } else if (repository.deliveries().submitting(job, clock.instant())) {
                        // All database work and the final eligibility check run on the dedicated worker.
                        if (eligibleToSubmit(job)) {
                            // Ping budget, keyed by this row: a retry of the same alert gets the same answer.
                            var mention = role.filter(r -> pingAllowed(job, clock.instant()));
                            var request = channel.sendMessage(FreebieMessages.alert(offer, mention, reference))
                                    .setNonce(FreebieMessages.nonce(job.id()))
                                    .timeout(SEND_TIMEOUT.toSeconds(), TimeUnit.SECONDS).submit();
                            submitted = true;
                            request.whenComplete((message, sendError) -> dispatch(job, () -> {
                                try {
                                    if (sendError == null) repository.markSent(job, message.getId(), clock.instant());
                                    else outcome(job, offer, FreebieFailure.classify(sendError), FailureDiagnostics.describe(sendError));
                                } catch (RuntimeException storage) { log(storage); }
                                finally { done(job); }
                            }));
                        }
                    } else repository.deliveries().block(job, FreebieDeliveryStore.Block.EXHAUSTED, clock.instant());
                }
            } catch (RuntimeException failure) {
                // Submission may have happened; the durable marker survives every storage failure.
                log(failure);
            } finally { if (!submitted) done(job); }
        }));
        return true;
    }

    private boolean eligible(FreebieRepository.Claimed job) {
        if (closed || !repository.deliveries().owns(job, clock.instant())) return false;
        var current = repository.offer(job.offerId()).orElse(null);
        if (current == null || current.ended(clock.instant())
                || (current.state() != FreebieOffer.State.APPROVED && current.state() != FreebieOffer.State.COMPLETED)) {
            repository.markCancelled(job, "offer no longer eligible", clock.instant());
            return false;
        }
        if (subscriptions != null) {
            var row = repository.deliveries().get(job.id()).orElse(null);
            if (row == null) return false;
            if (row.getInteger("manualRetries", 0) > 0
                    && !FreebieDeliveryAdmin.destination(row, current, subscriptions, jda.get())) {
                repository.deliveries().block(job, FreebieDeliveryStore.Block.DESTINATION, clock.instant());
                return false;
            }
        }
        if (repository.sendingPaused()) {
            repository.release(job, clock.instant().plusSeconds(120), clock.instant());
            return false;
        }
        // Subscription and pause reads may take time; re-read eligibility after them before REST submission.
        current = repository.offer(job.offerId()).orElse(null);
        if (current == null || current.ended(clock.instant())
                || (current.state() != FreebieOffer.State.APPROVED && current.state() != FreebieOffer.State.COMPLETED)) {
            repository.markCancelled(job, "offer no longer eligible", clock.instant());
            return false;
        }
        return repository.deliveries().owns(job, clock.instant());
    }

    /**
     * The final check immediately before REST, right after {@link #eligible} passed and the row was marked SUBMITTING.
     * The owner-retry destination was checked by that call and the SUBMITTING write changed nothing it depends on, so
     * it is not read again; the pause flag, the offer and the lease are, in that order, so the offer read is the last
     * read of the game before Discord is asked.
     */
    private boolean eligibleToSubmit(FreebieRepository.Claimed job) {
        if (closed || !repository.deliveries().owns(job, clock.instant())) return false;
        if (repository.sendingPaused()) {
            repository.release(job, clock.instant().plusSeconds(120), clock.instant());
            return false;
        }
        var current = repository.offer(job.offerId()).orElse(null);
        if (current == null || current.ended(clock.instant())
                || (current.state() != FreebieOffer.State.APPROVED && current.state() != FreebieOffer.State.COMPLETED)) {
            repository.markCancelled(job, "offer no longer eligible", clock.instant());
            return false;
        }
        return repository.deliveries().owns(job, clock.instant());
    }

    private void dispatch(FreebieRepository.Claimed job, Runnable action) {
        try { executor.execute(action); }
        catch (RejectedExecutionException rejected) { done(job); } // Lease remains recoverable after shutdown.
    }

    private static void log(RuntimeException failure) {
        BunnyLog.error("[Freebies] Delivery recovery failed | " + FailureDiagnostics.describe(failure));
    }
    private boolean failed(FreebieRepository.Claimed job, FreebieOffer offer, FreebieFailure failure, String detail) {
        if (job.verifyFirst()) repository.deliveries().block(job, FreebieDeliveryStore.Block.DESTINATION, clock.instant());
        else outcome(job, offer, failure, detail);
        return false;
    }

    private void outcome(FreebieRepository.Claimed job, FreebieOffer offer, FreebieFailure failure, String detail) {
        if (job.verifyFirst() || failure == FreebieFailure.TRANSIENT) {
            repository.deliveries().block(job, FreebieDeliveryStore.Block.UNCERTAIN, clock.instant());
            return;
        }
        Duration delay = failure.retryable() ? FreebieFailure.backoff(job.attempts()) : null;
        Instant next = delay == null ? null : clock.instant().plus(delay);
        if (next != null && !offer.ended(next)) {
            repository.markRetry(job, failure, detail, next, true, clock.instant());
            BunnyLog.warning("[Freebies] Delivery " + job.id() + " attempt " + job.attempts() + " failed (" + failure + "), retrying in " + delay);
            return;
        }
        if (repository.markFailed(job, failure, detail, clock.instant())) notifier.deliveryFailed(job, offer, failure);
    }
}







