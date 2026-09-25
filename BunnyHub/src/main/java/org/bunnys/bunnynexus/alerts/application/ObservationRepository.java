package org.bunnys.bunnynexus.alerts.application;

import org.bunnys.bunnynexus.alerts.domain.*;
import java.time.Instant;
import java.util.Objects;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;

/** Transactional offer/outbox port. Source certification and canonical mapping precede this boundary. */
public interface ObservationRepository {
    record SourceLease(SourceId source, String scope, String token, long generation) {
        public SourceLease {
            Objects.requireNonNull(source); scope = AlertIdentity.token(scope); token = AlertIdentity.token(token);
            if (generation < 1) throw new IllegalArgumentException("Invalid generation.");
        }
    }
    record Commit(String eventId, String automaticPlanId, Offer offer, Instant observedAt, SourceLease sourceLease,
                  long expectedContentRevision, long sourceOrder) {
        public Commit {
            eventId = token(eventId); automaticPlanId = token(automaticPlanId);
            Objects.requireNonNull(offer); Objects.requireNonNull(observedAt);
            Objects.requireNonNull(sourceLease);
            if (!sourceLease.source().equals(offer.evidence().source())) throw new IllegalArgumentException("Source mismatch.");
            if (offer.verifiedAt().isAfter(observedAt)) throw new IllegalArgumentException("Future verification.");
            if (expectedContentRevision < 0 || sourceOrder < 0 || offer.contentRevision() != Math.incrementExact(expectedContentRevision))
                throw new IllegalArgumentException("Expected content revision and source order are required.");
        }
        public FreeGameKey notificationKey() { return new FreeGameKey(offer.key()); }
    }
    enum Result { EVENT_CREATED, OBSERVATION_UPDATED, STALE_OBSERVATION, BACKPRESSURE }
    /**
     * In one bounded transaction evaluate policy, reject stale source generation/order, update the offer,
     * and create the unique canonical free event plus unique automatic audience plan when first eligible.
     * A manual event already present must be reused, adding the missing automatic plan without replacing
     * its snapshot. IDs are supplied before transaction retries; uniqueness must be enforced by indexes.
     * Do not advance polling cursors on backpressure. No HTTP/Discord calls in a transaction callback.
     * Verify sourceLease against current source/scope ownership and database-time expiry. Late generations
     * cannot update observations. Each certified source must additionally define its own data ordering rule;
     * this port does not assume that observation time establishes upstream data freshness.
     */
    Result commit(Commit command, FreeGamePolicy policy);
}
