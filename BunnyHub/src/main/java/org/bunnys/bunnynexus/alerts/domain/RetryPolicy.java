package org.bunnys.bunnynexus.alerts.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Bounded backoff for proven nonacceptance only. Budgets are supplied by the composition root. */
public record RetryPolicy(int maximumAttempts, Duration maximumJobAge, Duration baseDelay, Duration maximumDelay) {
    public RetryPolicy {
        if (maximumAttempts < 1) throw new IllegalArgumentException("Attempt limit must be positive.");
        for (Duration duration : new Duration[] { maximumJobAge, baseDelay, maximumDelay }) {
            Objects.requireNonNull(duration);
            if (duration.isNegative() || duration.toMillis() < 1) throw new IllegalArgumentException("Budget must be positive.");
        }
        if (baseDelay.compareTo(maximumDelay) > 0) throw new IllegalArgumentException("Base exceeds cap.");
    }
    /** jitter is a caller-supplied uniform sample in [0,1); retryNotBefore preserves remote backoff. */
    public Optional<Instant> next(DeliveryJob job, Instant now, Instant offerValidUntil,
                                  Optional<Instant> retryNotBefore, double jitter) {
        Objects.requireNonNull(job); Objects.requireNonNull(now); Objects.requireNonNull(offerValidUntil);
        Objects.requireNonNull(retryNotBefore);
        if (!Double.isFinite(jitter) || jitter < 0 || jitter >= 1) throw new IllegalArgumentException("Invalid jitter.");
        if (job.attemptCount() >= maximumAttempts) return Optional.empty();
        Instant deadline = job.createdAt().plus(maximumJobAge);
        if (offerValidUntil.isBefore(deadline)) deadline = offerValidUntil;
        long cap = maximumDelay.toMillis();
        long delay = baseDelay.toMillis();
        for (int i = 0; i < Math.min(job.attemptCount(), 63) && delay < cap; i++)
            delay = delay > cap / 2 ? cap : Math.min(cap, delay * 2);
        Instant due = now.plusMillis(Math.max(1, (long) (jitter * delay)));
        if (retryNotBefore.isPresent() && retryNotBefore.get().isAfter(due)) due = retryNotBefore.get();
        return due.isBefore(deadline) ? Optional.of(due) : Optional.empty();
    }
}
