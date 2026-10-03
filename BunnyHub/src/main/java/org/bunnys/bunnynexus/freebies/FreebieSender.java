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
import java.util.Optional;
import java.util.concurrent.*;
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
    private static final Duration LEASE = Duration.ofMinutes(3), SEND_TIMEOUT = Duration.ofSeconds(45);

    private final FreebieRepository repository;
    private final Supplier<JDA> jda;
    private final ScheduledExecutorService executor;
    private final Clock clock;
    private final FreebieNotifier notifier;
    private final AtomicInteger inFlight = new AtomicInteger();
    private FreebieSubscriptions subscriptions;
    private volatile boolean closed;

    FreebieSender(FreebieRepository repository, Supplier<JDA> jda, ScheduledExecutorService executor, Clock clock, FreebieNotifier notifier) {
        this.repository = repository; this.jda = jda; this.executor = executor; this.clock = clock; this.notifier = notifier;
    }

    FreebieSender withSubscriptions(FreebieSubscriptions value) { subscriptions = value; return this; }

    int inFlight() { return inFlight.get(); }
    void close() { closed = true; }

    /** Called every second on the sender thread. */
    void tick() {
        JDA client = jda.get();
        if (closed || client == null || client.getStatus() != JDA.Status.CONNECTED) return;
        if (repository.sendingPaused()) return; // Global kill switch: queued work waits, nothing is lost.

        repository.recoverExpiredLeases(clock.instant());
        while (!closed && inFlight.get() < MAX_IN_FLIGHT) {
            var claimed = repository.claim(clock.instant(), LEASE);
            if (claimed.isEmpty()) return;
            inFlight.incrementAndGet();
            // Exactly one release per claim: here for synchronous outcomes, in the callback otherwise.
            boolean handedOff = false;
            try { handedOff = start(client, claimed.get()); }
            finally { if (!handedOff) done(); }
        }
    }

    private void done() { inFlight.decrementAndGet(); }

    /** @return true when a Discord request is in flight and its callback will call {@link #done()} */
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
        if (channel == null) return failed(job, offer, FreebieFailure.CHANNEL_MISSING, "channel not found");
        var self = guild.getSelfMember();
        if (!self.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS))
            return failed(job, offer, FreebieFailure.MISSING_PERMISSIONS, "missing view/send/embed");
        // A deleted role must not block the alert; it just goes out without a ping.
        Optional<String> role = job.roleId().filter(id -> guild.getRoleById(id) != null);
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
        existing.whenComplete((found, error) -> dispatch(() -> {
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
                        if (eligible(job)) {
                            var request = channel.sendMessage(FreebieMessages.alert(offer, role, reference))
                                    .setNonce(FreebieMessages.nonce(job.id()))
                                    .timeout(SEND_TIMEOUT.toSeconds(), TimeUnit.SECONDS).submit();
                            submitted = true;
                            request.whenComplete((message, sendError) -> dispatch(() -> {
                                try {
                                    if (sendError == null) repository.markSent(job, message.getId(), clock.instant());
                                    else outcome(job, offer, FreebieFailure.classify(sendError), FailureDiagnostics.describe(sendError));
                                } catch (RuntimeException storage) { log(storage); }
                                finally { done(); }
                            }));
                        }
                    } else repository.deliveries().block(job, FreebieDeliveryStore.Block.EXHAUSTED, clock.instant());
                }
            } catch (RuntimeException failure) {
                // Submission may have happened; the durable marker survives every storage failure.
                log(failure);
            } finally { if (!submitted) done(); }
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

    private void dispatch(Runnable action) {
        try { executor.execute(action); }
        catch (RejectedExecutionException rejected) { done(); } // Lease remains recoverable after shutdown.
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







