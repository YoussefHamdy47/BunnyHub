package org.bunnys.bunnynexus.alerts.application;

import org.bunnys.bunnynexus.alerts.domain.DeliveryJob;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.token;

/**
 * Durable storage boundary; intentionally no in-memory production implementation.
 * All calls have finite operation deadlines and run outside gateway/callback/interactive executors.
 * Implementations need real replica-set crash/race tests before activation.
 */
public interface DeliveryRepository extends DeliveryQueue {
    /** Claim at most one due job, using database time; caller reserves an asynchronous in-flight slot first. */
    Optional<DeliveryJob> claim(String jobId, String ownerToken, Duration leaseDuration);

    /** Trusted transport preflight evidence, obtained outside the transaction and bound to exact roles/destination. */
    record PermissionCheck(org.bunnys.bunnynexus.alerts.domain.AlertIdentity.DestinationRef destination,
                           java.util.Set<String> roles, java.time.Instant validUntil) {
        public PermissionCheck {
            Objects.requireNonNull(destination); Objects.requireNonNull(validUntil);
            roles = org.bunnys.bunnynexus.alerts.domain.Subscription.checkedRoles(roles, destination.guildId());
        }
    }
    record AuthorizationRequest(String jobId, long expectedRevision, DeliveryJob.Lease owner,
                                AutomaticSendEngine.PreparedPayload payload, PermissionCheck permissionCheck,
                                OwnershipRepository.Lease runtimeLease) {
        public AuthorizationRequest {
            jobId = token(jobId); Objects.requireNonNull(owner); Objects.requireNonNull(payload);
            Objects.requireNonNull(permissionCheck);
            Objects.requireNonNull(runtimeLease);
            if (!(runtimeLease.resource() instanceof OwnershipRepository.Runtime)) throw new IllegalArgumentException("Runtime ownership required.");
            if (expectedRevision < 1) throw new IllegalArgumentException("Invalid revision.");
        }
    }
    /**
     * Load job + its event + current offer + guild/destination/subscription guards, using the same session.
     * Evaluate the pure rule and touch all guards when authorizing, forcing conflicts with configuration writes.
     * Persist SENDING and its attempt atomically, conditioned on revision/state/token/generation and DB-time expiry.
     * Stable attempt ID and payload come from request, outside retryable callbacks. Never contact Discord here.
     * Empty means ownership/revision conflict. Returning Sending requires known successful commit, not unknown commit.
     * Blocked leaves the job leased; caller must boundedly defer, release via recovery, or repair it, never busy-loop.
     */
    Optional<AutomaticSendEngine.Decision> authorize(AuthorizationRequest request, AutomaticSendEngine rule);

    /**
     * Defer a still-LEASED job after blocked preflight. This never creates an attempt or changes identity.
     * The explicit operation replaces the original arbitrary saveTransition port so callers cannot bypass
     * authorization by supplying a fabricated SENDING/SENT snapshot. Unknown commit errors propagate.
     */
    Optional<DeliveryJob> defer(String jobId, long expectedRevision, DeliveryJob.Lease owner, java.time.Instant retryAt);
}
