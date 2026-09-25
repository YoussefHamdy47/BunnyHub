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
import java.util.Map;
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
    private final Map<String, FreebieOffer> offerCache = new ConcurrentHashMap<>();
    private volatile boolean closed;

    FreebieSender(FreebieRepository repository, Supplier<JDA> jda, ScheduledExecutorService executor, Clock clock, FreebieNotifier notifier) {
        this.repository = repository; this.jda = jda; this.executor = executor; this.clock = clock; this.notifier = notifier;
    }

    int inFlight() { return inFlight.get(); }
    void close() { closed = true; }

    /** Called every second on the sender thread. */
    void tick() {
        JDA client = jda.get();
        if (closed || client == null || client.getStatus() != JDA.Status.CONNECTED) return;
        if (repository.sendingPaused()) return; // Global kill switch: queued work waits, nothing is lost.
        offerCache.clear();
        repository.recoverExpiredLeases(clock.instant());
        while (!closed && inFlight.get() < MAX_IN_FLIGHT) {
            var claimed = repository.claim(clock.instant(), LEASE);
            if (claimed.isEmpty()) return;
            inFlight.incrementAndGet();
            try { start(client, claimed.get()); }
            catch (RuntimeException failure) { done(); throw failure; }
        }
    }

    private void done() { inFlight.decrementAndGet(); }

    private void start(JDA client, FreebieRepository.Claimed job) {
        Instant now = clock.instant();
        FreebieOffer offer = offerCache.computeIfAbsent(job.offerId(), id -> repository.offer(id).orElse(null));
        // COMPLETED still allows catch-up sends for late subscribers; STOPPED/REJECTED/EXPIRED never send.
        if (offer == null || (offer.state() != FreebieOffer.State.APPROVED && offer.state() != FreebieOffer.State.COMPLETED)) {
            repository.markCancelled(job, "offer no longer approved", now); done(); return;
        }
        if (offer.ended(now)) { repository.markCancelled(job, "offer ended", now); done(); return; }

        if (client.isUnavailable(Long.parseLong(job.guildId()))) {
            // Discord outage for this guild: not the server's fault and not an attempt.
            repository.release(job, now.plus(Duration.ofMinutes(2))); done(); return;
        }
        Guild guild = client.getGuildById(job.guildId());
        if (guild == null) { fail(job, offer, FreebieFailure.BOT_REMOVED, "guild not in cache"); return; }
        var channel = guild.getChannelById(StandardGuildMessageChannel.class, job.channelId());
        if (channel == null) { fail(job, offer, FreebieFailure.CHANNEL_MISSING, "channel not found"); return; }
        var self = guild.getSelfMember();
        if (!self.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)) {
            fail(job, offer, FreebieFailure.MISSING_PERMISSIONS, "missing view/send/embed"); return;
        }
        // A deleted role must not block the alert; it just goes out without a ping.
        Optional<String> role = job.roleId().filter(id -> guild.getRoleById(id) != null);
        String reference = FreebieMessages.reference(job.id());

        CompletableFuture<Optional<Message>> existing = job.verifyFirst() && self.hasPermission(channel, Permission.MESSAGE_HISTORY)
                ? channel.getHistory().retrievePast(50).timeout(SEND_TIMEOUT.toSeconds(), TimeUnit.SECONDS).submit()
                    .thenApply(messages -> messages.stream()
                            .filter(m -> m.getAuthor().getId().equals(client.getSelfUser().getId()))
                            .filter(m -> FreebieMessages.carriesReference(m, reference)).findFirst())
                : CompletableFuture.completedFuture(Optional.empty());

        existing.thenCompose(found -> found.isPresent() ? CompletableFuture.completedFuture(found.get())
                        : channel.sendMessage(FreebieMessages.alert(offer, role, reference))
                            .setNonce(FreebieMessages.nonce(job.id()))
                            .timeout(SEND_TIMEOUT.toSeconds(), TimeUnit.SECONDS).submit())
                .whenCompleteAsync((message, error) -> {
                    try {
                        if (error == null) repository.markSent(job, message.getId(), clock.instant());
                        else outcome(job, offer, FreebieFailure.classify(error), FailureDiagnostics.describe(error));
                    } catch (RuntimeException storage) {
                        // The lease expires and recovery re-checks the channel before any resend.
                        BunnyLog.error("[Freebies] Could not record delivery " + job.id() + " | " + FailureDiagnostics.describe(storage));
                    } finally { done(); }
                }, executor);
    }

    private void outcome(FreebieRepository.Claimed job, FreebieOffer offer, FreebieFailure failure, String detail) {
        Duration delay = failure.retryable() ? FreebieFailure.backoff(job.attempts()) : null;
        Instant next = delay == null ? null : clock.instant().plus(delay);
        if (next != null && !offer.ended(next)) {
            repository.markRetry(job, failure, detail, next, true);
            BunnyLog.warning("[Freebies] Delivery " + job.id() + " attempt " + job.attempts() + " failed (" + failure + "), retrying in " + delay);
            return;
        }
        if (repository.markFailed(job, failure, detail, clock.instant())) notifier.deliveryFailed(job, offer, failure);
    }

    private void fail(FreebieRepository.Claimed job, FreebieOffer offer, FreebieFailure failure, String detail) {
        try { outcome(job, offer, failure, detail); } finally { done(); }
    }
}
