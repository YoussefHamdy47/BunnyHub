package org.bunnys.bunnynexus.alerts.application;

import org.bunnys.bunnynexus.alerts.domain.*;
import java.time.Instant;
import java.util.Optional;

/** Owner IDs come from verified interactions; repository composition supplies the one trusted bot owner. */
public interface OwnerReviewRepository {
    enum State { PENDING, VERIFIED, RELEASED, REVOKED }
    enum Status { APPLIED, REPLAYED, CONFLICT }
    record Result(Status status, long revision) {}
    record Snapshot(String id, long revision, State state, Offer offer, AlertDisplayContent content,
                    String materialHash, String templateVersion, Instant previewUntil, Optional<Instant> releaseUntil,
                    int maximumAttempts, int usedAttempts) {}
    Optional<Snapshot> get(String actorId, AlertIdentity.OfferKey key);
    Result prepare(String actorId, String actionId, long expectedRevision, Offer offer, AlertDisplayContent content);
    Result verify(String actorId, String actionId, AlertIdentity.OfferKey key, long expectedRevision);
    Result release(String actorId, String actionId, AlertIdentity.OfferKey key, long expectedRevision, int maximumAttempts);
    Result revoke(String actorId, String actionId, AlertIdentity.OfferKey key, long expectedRevision);
}
