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
    private final GamerPowerFeed feed;
    private final Clock clock;
    private final FreebieReviewChannel reviews;
    private final BiConsumer<FreebieOffer, String> ended;
    private final Instant startedAt;
    private ScheduledExecutorService executor;

    // Written on the discovery thread only; the volatile ones are also read by /freebie-admin status.
    private int failures;
    private boolean healthAlerted;
    private volatile Instant lastPollAt, lastPollOk;
    private volatile String lastProblem = "not polled yet";

    FreebieDiscovery(FreebieConfig config, FreebieRepository repository, GamerPowerFeed feed, Clock clock,
                     FreebieReviewChannel reviews, BiConsumer<FreebieOffer, String> ended) {
        this.config = config; this.repository = repository; this.feed = feed; this.clock = clock;
        this.reviews = reviews; this.ended = ended;
        startedAt = clock.instant();
    }

    void start(ScheduledExecutorService discoveryThread, long initialDelaySeconds) {
        executor = discoveryThread;
        executor.schedule(this::pollAndReschedule, initialDelaySeconds, TimeUnit.SECONDS);
    }

    /** One extra check on the discovery thread; the regular schedule is unchanged. */
    void pollNow() {
        executor.execute(() -> {
            try { poll(); }
            catch (RuntimeException failure) { failed(failure); }
        });
    }

    String status() {
        return "Last GamerPower check: " + (lastPollAt == null ? "not yet" : "<t:" + lastPollAt.getEpochSecond() + ":R>")
                + (lastProblem.isEmpty() ? " ✅" : " ⚠️ " + lastProblem) + "\n"
                + "Last successful check: " + (lastPollOk == null ? "none since start" : "<t:" + lastPollOk.getEpochSecond() + ":R>");
    }

    private void pollAndReschedule() {
        Duration next;
        // Anything thrown must still reschedule, or discovery would silently stop for good.
        try { next = poll(); }
        catch (Throwable failure) { next = failed(failure); }
        try {
            if (!executor.isShutdown()) executor.schedule(this::pollAndReschedule, next.toSeconds(), TimeUnit.SECONDS);
        } catch (RejectedExecutionException shuttingDown) { /* Shutdown raced the reschedule. */ }
    }

    private Duration poll() {
        var fetch = feed.fetch();
        Instant now = clock.instant();
        lastPollAt = now;
        var batch = fetch.batch().filter(b -> b.state() != GamerPowerIntake.State.FAILED);
        if (batch.isEmpty()) {
            failures++;
            lastProblem = fetch.problem();
            BunnyLog.warning("[Freebies] GamerPower poll failed: " + fetch.problem());
            checkHealth(now);
            Duration backoff = backoff();
            return fetch.retryAfter().filter(wait -> wait.compareTo(backoff) > 0).orElse(backoff);
        }
        failures = 0;
        lastPollOk = now;
        lastProblem = "";
        checkHealth(now);
        int fresh = 0;
        Set<String> present = new HashSet<>();
        for (var candidate : batch.get().candidates()) {
            present.add(FreebieOffer.idFor(candidate.sourceItemId()));
            if (repository.recordSeen(candidate, now)) fresh++;
        }
        // Only a complete, valid feed may be used as evidence that an offer ended.
        if (fetch.complete()) for (var gone : repository.markMissing(present)) ended.accept(gone, "no longer listed as active");
        if (fresh > 0) BunnyLog.info("[Freebies] " + fresh + " new giveaway(s) queued for review.");
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
        reviews.notifyOwners(unhealthy
                ? "⚠️ Free-game discovery has not worked for over an hour (last problem: " + lastProblem
                        + "). New giveaways may be missed until GamerPower is reachable again."
                : "✅ Free-game discovery is working again.");
    }
}
