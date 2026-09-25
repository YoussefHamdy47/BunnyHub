package org.bunnys.bunnynexus.alerts.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.stream.Stream;
import static org.bunnys.bunnynexus.alerts.domain.Offer.*;

/** Pure fail-closed classification. Callers supply policy budgets; no launch defaults are enabled here. */
public record FreeGamePolicy(Duration maximumEvidenceAge, boolean allowUnknownEnd) {
    public enum Decision { ELIGIBLE, INELIGIBLE, UNKNOWN, SCHEDULED, EXPIRED, STALE }
    public enum Reason { VERIFIED_FREE_GAME, WRONG_KIND, UNPROVEN_KIND, REJECTED_EVIDENCE,
        MISSING_EVIDENCE, UNKNOWN_PRICE, NONZERO_PRICE, UNKNOWN_START, UNKNOWN_END,
        NOT_STARTED, WINDOW_ENDED, FUTURE_EVIDENCE, EVIDENCE_TOO_OLD }
    public record Result(Decision decision, Reason reason) {
        public boolean eligible() { return decision == Decision.ELIGIBLE; }
    }
    public FreeGamePolicy {
        Objects.requireNonNull(maximumEvidenceAge);
        if (maximumEvidenceAge.isZero() || maximumEvidenceAge.isNegative())
            throw new IllegalArgumentException("Freshness budget must be positive.");
    }
    public Result evaluate(Offer offer, Instant now) {
        Objects.requireNonNull(offer); Objects.requireNonNull(now);
        if (offer.kind() == Kind.UNKNOWN) return result(Decision.UNKNOWN, Reason.UNPROVEN_KIND);
        if (offer.kind() != Kind.FREE_TO_KEEP_BASE_GAME) return result(Decision.INELIGIBLE, Reason.WRONG_KIND);
        Evidence e = offer.evidence();
        if (Stream.of(e.market(), e.entitlement(), e.claimConditions(), e.approvedLink()).anyMatch(p -> p == Proof.REJECTED))
            return result(Decision.INELIGIBLE, Reason.REJECTED_EVIDENCE);
        if (Stream.of(e.market(), e.entitlement(), e.claimConditions(), e.approvedLink()).anyMatch(p -> p == Proof.UNKNOWN))
            return result(Decision.UNKNOWN, Reason.MISSING_EVIDENCE);
        if (offer.price().isEmpty()) return result(Decision.UNKNOWN, Reason.UNKNOWN_PRICE);
        if (offer.price().get().minorUnits() != 0) return result(Decision.INELIGIBLE, Reason.NONZERO_PRICE);
        if (offer.endsAt().isPresent() && !now.isBefore(offer.endsAt().get()))
            return result(Decision.EXPIRED, Reason.WINDOW_ENDED);
        if (offer.startsAt().isEmpty()) return result(Decision.UNKNOWN, Reason.UNKNOWN_START);
        if (now.isBefore(offer.startsAt().get())) return result(Decision.SCHEDULED, Reason.NOT_STARTED);
        if (offer.endsAt().isEmpty() && !allowUnknownEnd) return result(Decision.UNKNOWN, Reason.UNKNOWN_END);
        if (offer.verifiedAt().isAfter(now)) return result(Decision.UNKNOWN, Reason.FUTURE_EVIDENCE);
        if (Duration.between(offer.verifiedAt(), now).compareTo(maximumEvidenceAge) >= 0)
            return result(Decision.STALE, Reason.EVIDENCE_TOO_OLD);
        return result(Decision.ELIGIBLE, Reason.VERIFIED_FREE_GAME);
    }
    /** Exclusive authorization deadline, bounded by both evidence freshness and known expiry. */
    public Instant validUntil(Offer offer) {
        Instant freshUntil = offer.verifiedAt().plus(maximumEvidenceAge);
        return offer.endsAt().filter(end -> end.isBefore(freshUntil)).orElse(freshUntil);
    }
    private static Result result(Decision decision, Reason reason) { return new Result(decision, reason); }
}
