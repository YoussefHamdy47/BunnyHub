package org.bunnys.bunnynexus.freebies;

import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Polls GamerPower on the discovery thread: new giveaways are queued for review, repeated absence from a complete
 * feed ends an offer, failures back off (up to 2 h, honouring Retry-After), and the owners hear once when discovery
 * has been broken for an hour and once when it recovers.
 */
final class FreebieDiscovery {
    private static final int MAX_BACKOFF_MINUTES = 120;
    private static final Duration HEALTH_ALERT_AFTER = Duration.ofHours(1);

    private final FreebieConfig config;
    private final FreebieRepository repository;
    private final FreebieFeed feed;
    private final Clock clock;
    private final FreebieReviewChannel reviews;
    private final BiConsumer<FreebieOffer, String> ended;
    private final Instant startedAt;
    private ScheduledExecutorService executor;
    /** Runs on the discovery thread after every poll that reached the feed; failures never affect polling. */
    private volatile Runnable afterPoll = () -> {};

    // Written on the discovery thread only; the volatile ones are also read by /freebie-admin status.
    private int failures;
    private boolean healthAlerted;
    private volatile Instant retryNotBefore = Instant.MIN;
    private volatile Instant lastPollAt, lastPollOk;
    private volatile String lastProblem = "not polled yet";

    FreebieDiscovery(FreebieConfig config, FreebieRepository repository, FreebieFeed feed, Clock clock,
                     FreebieReviewChannel reviews, BiConsumer<FreebieOffer, String> ended) {
        this.config = config; this.repository = repository; this.feed = feed; this.clock = clock;
        this.reviews = reviews; this.ended = ended;
        startedAt = clock.instant();
    }

    void afterPoll(Runnable task) { afterPoll = task; }

    void start(ScheduledExecutorService discoveryThread, long initialDelaySeconds) {
        executor = discoveryThread;
        executor.schedule(this::pollAndReschedule, initialDelaySeconds, TimeUnit.SECONDS);
    }

    /** One extra check on the discovery thread, respecting failure backoff; the regular schedule is unchanged. */
    void pollNow() {
        executor.execute(this::attempt);
    }

    String status() {
        return "Last " + feed.provider().label + " check: " + (lastPollAt == null ? "not yet" : "<t:" + lastPollAt.getEpochSecond() + ":R>")
                + (lastProblem.isEmpty() ? " ✅" : " ⚠️ " + lastProblem) + "\n"
                + "Last successful check: " + (lastPollOk == null ? "none since start" : "<t:" + lastPollOk.getEpochSecond() + ":R>")
                + (clock.instant().isBefore(retryNotBefore) ? "\nDiscovery retry: <t:" + retryNotBefore.getEpochSecond() + ":R>" : "");
    }

    private void pollAndReschedule() {
        Duration next = attempt();
        try {
            if (!executor.isShutdown()) executor.schedule(this::pollAndReschedule, Math.max(1, next.toSeconds()), TimeUnit.SECONDS);
        } catch (RejectedExecutionException shuttingDown) { /* Shutdown raced the reschedule. */ }
    }

    private Duration attempt() {
        Instant now = clock.instant();
        if (now.isBefore(retryNotBefore)) return Duration.between(now, retryNotBefore);
        lastPollAt = now;
        Duration next;
        // Anything thrown must still reschedule, or discovery would silently stop for good.
        try { next = poll(); }
        catch (Throwable failure) { next = failed(failure); }
        retryNotBefore = failures > 0 ? clock.instant().plus(next) : Instant.MIN;
        return next;
    }

    private Duration poll() {
        var fetch = feed.poll();
        Instant now = clock.instant();
        
        if (fetch.state() == FreebieFeed.State.FAILED) {
            failures++;
            lastProblem = fetch.problem();
            BunnyLog.warning("[Freebies] " + feed.provider().label + " poll failed: " + fetch.problem());
            checkHealth(now);
            Duration backoff = backoff();
            return fetch.retryAfter().filter(wait -> wait.compareTo(backoff) > 0).orElse(backoff);
        }
        int fresh = 0;
        Set<String> present = new HashSet<>();
        for (var candidate : fetch.candidates()) {
            if (candidate.provider() != feed.provider()) throw new IllegalArgumentException("Mismatched feed provider");
            present.add(candidate.identity());
            present.add(FreebieSourceStore.canonicalId(candidate));
            if (repository.recordSeen(candidate, now)) fresh++;
        }
        // Only a complete, valid feed may be used as evidence that an offer ended.
        if (fetch.complete()) for (var gone : repository.markMissing(feed.provider(), present)) ended.accept(gone, "no longer listed as active");
        if (fresh > 0) BunnyLog.info("[Freebies] " + fresh + " new giveaway(s) queued for review.");
        try { afterPoll.run(); }
        catch (RuntimeException failure) {
            BunnyLog.warning("[Freebies] Post-poll task failed | " + FailureDiagnostics.describe(failure));
        }
        if (!fetch.complete()) {
            failures++;
            lastProblem = fetch.problem();
            checkHealth(now);
            return backoff();
        }
        // Fetching alone is not recovery: all accepted rows and absence checks must reach storage first.
        failures = 0;
        lastPollOk = clock.instant();
        lastProblem = "";
        checkHealth(lastPollOk);
        return Duration.ofMinutes(config.pollMinutes());
    }

    private Duration failed(Throwable failure) {
        failures++;
        lastProblem = "error: " + failure.getClass().getSimpleName();
        BunnyLog.error("[Freebies] Poll failed | " + FailureDiagnostics.describe(failure));
        checkHealth(clock.instant());
        return backoff();
    }

    private Duration backoff() {
        long minutes = (long) config.pollMinutes() << Math.min(failures, 4);
        return Duration.ofMinutes(Math.min(minutes, MAX_BACKOFF_MINUTES));
    }

    private void checkHealth(Instant now) {
        Instant reference = lastPollOk != null ? lastPollOk : startedAt;
        boolean unhealthy = Duration.between(reference, now).compareTo(HEALTH_ALERT_AFTER) >= 0;
        if (unhealthy == healthAlerted) return;
        healthAlerted = unhealthy;
        try {
            reviews.notifyOwners(unhealthy
                    ? "⚠️ " + feed.provider().label + " free-game discovery has not worked for over an hour (last problem: " + lastProblem
                            + "). New giveaways may be missed until complete feed processing recovers."
                    : "✅ " + feed.provider().label + " free-game discovery is working again.");
        } catch (RuntimeException noticeFailure) {
            // A synchronous Discord failure must never stop polling or replace the discovery outcome.
            BunnyLog.warning("[Freebies] Discovery health notice failed | " + FailureDiagnostics.describe(noticeFailure));
        }
    }
}

