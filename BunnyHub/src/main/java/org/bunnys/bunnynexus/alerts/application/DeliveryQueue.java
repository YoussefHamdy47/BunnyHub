package org.bunnys.bunnynexus.alerts.application;

import org.bunnys.bunnynexus.alerts.domain.DeliveryJob;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable queue lifecycle, separate from the not-yet-integrated send authorization repository. */
public interface DeliveryQueue {
    List<DeliveryJob> due(DeliveryJob.State state, String guildId, int limit);
    Optional<DeliveryJob> claim(String jobId, String ownerToken, Duration duration);
    Optional<DeliveryJob> recoverExpired(String jobId, long expectedRevision);
    /** RetryAt must come from the configured RetryPolicy; transport supplies explicit acceptance evidence. */
    Optional<DeliveryJob> recordOutcome(String jobId, long expectedRevision, DeliveryJob.Lease owner,
                                       String attemptId, DeliveryJob.Outcome outcome, Optional<Instant> retryAt);
}
