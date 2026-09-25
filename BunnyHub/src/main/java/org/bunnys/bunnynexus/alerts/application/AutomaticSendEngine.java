package org.bunnys.bunnynexus.alerts.application;

import org.bunnys.bunnynexus.alerts.domain.*;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;

/**
 * Pure authorization rule, run against current guard records inside the repository transaction.
 * A Sending result is only a proposed state until that transaction commits successfully.
 */
public final class AutomaticSendEngine {
    private final FreeGamePolicy policy;
    public AutomaticSendEngine(FreeGamePolicy policy) { this.policy = Objects.requireNonNull(policy); }

    public record Snapshot(DeliveryJob job, Subscription subscription, Offer offer, String eventId, FreeGameKey eventKey,
                           Instant observedAt, Instant databaseNow, boolean destinationPermissionsVerified, Instant permissionValidUntil) {
        public Snapshot(DeliveryJob job, Subscription subscription, Offer offer, String eventId, FreeGameKey eventKey,
                        Instant observedAt, Instant databaseNow, boolean destinationPermissionsVerified) {
            this(job, subscription, offer, eventId, eventKey, observedAt, databaseNow, destinationPermissionsVerified, Instant.MAX);
        }
        public Snapshot {
            Objects.requireNonNull(job); Objects.requireNonNull(subscription); Objects.requireNonNull(offer);
            eventId = token(eventId);
            if (!job.key().eventId().equals(eventId)) throw new IllegalArgumentException("Job/event mismatch.");
            Objects.requireNonNull(eventKey); Objects.requireNonNull(observedAt); Objects.requireNonNull(databaseNow);
            Objects.requireNonNull(permissionValidUntil);
            if (!offer.key().equals(eventKey.offer())) throw new IllegalArgumentException("Event/offer mismatch.");
            if (observedAt.isAfter(databaseNow)) throw new IllegalArgumentException("Future event observation.");
        }
    }
    /** Renderer output fingerprint; adapters must hash the exact serialized payload, including mentions. */
    public record PreparedPayload(OfferKey offerKey, String subscriptionId, DestinationRef destination,
                                  String attemptId, String nonce, long contentRevision, long subscriptionRevision,
                                  String templateVersion, String payloadHash, Set<String> approvedRoles, String reviewMaterialHash) {
        public PreparedPayload {
            Objects.requireNonNull(offerKey); subscriptionId = token(subscriptionId); Objects.requireNonNull(destination);
            attemptId = token(attemptId); nonce = token(nonce); templateVersion = token(templateVersion);
            if (contentRevision < 1 || subscriptionRevision < 1) throw new IllegalArgumentException("Invalid revision.");
            if (payloadHash == null || !payloadHash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid payload hash.");
            if (reviewMaterialHash == null || !reviewMaterialHash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid review material hash.");
            Objects.requireNonNull(approvedRoles);
            if (approvedRoles.size() > Subscription.MAX_ROLE_IDS) throw new IllegalArgumentException("Too many roles.");
            approvedRoles = Set.copyOf(approvedRoles); approvedRoles.forEach(AlertIdentity::snowflake);
        }
    }
    public sealed interface Decision permits Sending, Skipped, Blocked {}
    public record Sending(DeliveryJob job) implements Decision {}
    public record Skipped(DeliveryJob job) implements Decision {}
    public enum BlockReason { REFRESH_PAYLOAD, REFRESH_EVIDENCE, REPAIR_PERMISSIONS, OWNER_RELEASE_REQUIRED }
    public record Blocked(BlockReason reason) implements Decision {}

    public Decision authorize(Snapshot current, DeliveryJob.Lease owner, PreparedPayload payload) {
        Objects.requireNonNull(current); Objects.requireNonNull(owner); Objects.requireNonNull(payload);
        DeliveryJob job = current.job();
        Instant now = current.databaseNow();
        // Validate ownership even for a blocked decision; an expired worker must not continue preparing work.
        if (job.state() != DeliveryJob.State.LEASED || job.lease().isEmpty()
                || !job.lease().get().token().equals(owner.token())
                || job.generation() != owner.generation() || !now.isBefore(job.lease().get().until()))
            throw new IllegalStateException("Authorization requires a current leased job.");
        Subscription subscription = current.subscription();
        if (subscription.matchAutomatic(job.subscriptionId(), job.destination(), current.offer().key(),
                Topic.FREE_GAME, current.observedAt()) != Subscription.Match.MATCH)
            return new Skipped(job.skip(owner, now, DeliveryJob.Reason.CONFIGURATION_CHANGED));
        var eligibility = policy.evaluate(current.offer(), now);
        if (!eligibility.eligible()) {
            if (eligibility.decision() == FreeGamePolicy.Decision.EXPIRED
                    || eligibility.decision() == FreeGamePolicy.Decision.INELIGIBLE)
                return new Skipped(job.skip(owner, now, DeliveryJob.Reason.OFFER_INELIGIBLE));
            return new Blocked(BlockReason.REFRESH_EVIDENCE);
        }
        if (!current.destinationPermissionsVerified() || !now.isBefore(current.permissionValidUntil())) return new Blocked(BlockReason.REPAIR_PERMISSIONS);
        if (!payload.offerKey().equals(current.offer().key()) || !payload.subscriptionId().equals(subscription.id())
                || !payload.destination().equals(job.destination())
                || payload.contentRevision() != current.offer().contentRevision()
                || payload.subscriptionRevision() != subscription.revision()
                || !payload.approvedRoles().equals(subscription.roleIds()))
            return new Blocked(BlockReason.REFRESH_PAYLOAD);
        Instant deadline = policy.validUntil(current.offer());
        if (current.permissionValidUntil().isBefore(deadline)) deadline = current.permissionValidUntil();
        // Persist the same deadline enforced by the scheduler: a queued request must not outlive its job lease.
        if (job.lease().orElseThrow().until().isBefore(deadline)) deadline = job.lease().orElseThrow().until();
        var attempt = new DeliveryJob.Attempt(payload.attemptId(), Math.incrementExact(job.attemptCount()),
                payload.nonce(), payload.contentRevision(), payload.subscriptionRevision(), payload.templateVersion(),
                payload.payloadHash(), payload.approvedRoles(), now, deadline);
        return new Sending(job.authorize(owner, now, attempt));
    }
}
