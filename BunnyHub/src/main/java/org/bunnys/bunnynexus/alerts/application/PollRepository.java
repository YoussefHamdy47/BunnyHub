package org.bunnys.bunnynexus.alerts.application;

import java.time.Duration;
import java.util.Objects;
import org.bunnys.bunnynexus.alerts.domain.AlertIdentity;

/** Durable shadow polling schedule, not a certified ingestion cursor. Unknown writes propagate. */
public interface PollRepository {
    enum Outcome { SUCCESS, PARTIAL, FAILURE, PAUSED, BACKPRESSURE }
    record Completion(Outcome outcome, int candidates, int rejected) {
        public Completion {
            Objects.requireNonNull(outcome);
            if (candidates < 0 || rejected < 0 || candidates + (long) rejected > 500) throw new IllegalArgumentException("Invalid poll counts.");
        }
    }
    /** Reserve due work under current source ownership. No fetch without a known successful reservation. */
    boolean begin(OwnershipRepository.Lease lease, String attemptId, Duration interval);
    /** One conditional finish. Never repeat HTTP to resolve an uncertain finish. */
    boolean finish(OwnershipRepository.Lease lease, String attemptId, Duration interval, Completion result);
    static void validate(OwnershipRepository.Lease lease, String attempt, Duration interval) {
        Objects.requireNonNull(lease); AlertIdentity.token(attempt);
        if (!(lease.resource() instanceof OwnershipRepository.Source)) throw new IllegalArgumentException("Source lease required.");
        if (interval == null || interval.compareTo(Duration.ofSeconds(1)) < 0 || interval.compareTo(Duration.ofHours(1)) > 0
                || interval.getNano() % 1_000_000 != 0) throw new IllegalArgumentException("Polling interval must be 1 second to 1 hour in milliseconds.");
    }
}
