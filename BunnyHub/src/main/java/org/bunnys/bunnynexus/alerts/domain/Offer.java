package org.bunnys.bunnynexus.alerts.domain;

import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;

/** Already normalized evidence, not a raw source response. Unresolved campaign mappings stay outside this type. */
public record Offer(OfferKey key, String editionId, Kind kind, Evidence evidence,
                    Optional<Money> price, Optional<Instant> startsAt, Optional<Instant> endsAt,
                    Instant verifiedAt, long contentRevision) {
    public enum Kind { FREE_TO_KEEP_BASE_GAME, FREE_WEEKEND, PERMANENTLY_FREE, DLC, DEMO, PAID_GAME, UNKNOWN }
    public enum Proof { VERIFIED, REJECTED, UNKNOWN }
    public record Evidence(Proof market, Proof entitlement, Proof claimConditions, Proof approvedLink,
                           SourceId source, String sourceItemId, String identityRuleVersion) {
        public Evidence {
            Objects.requireNonNull(market); Objects.requireNonNull(entitlement);
            Objects.requireNonNull(claimConditions); Objects.requireNonNull(approvedLink);
            Objects.requireNonNull(source); sourceItemId = token(sourceItemId);
            identityRuleVersion = token(identityRuleVersion);
        }
    }
    public record Money(long minorUnits, Currency currency) {
        public Money {
            if (minorUnits < 0) throw new IllegalArgumentException("Negative price.");
            Objects.requireNonNull(currency);
        }
    }
    public Offer {
        Objects.requireNonNull(key); editionId = token(editionId);
        Objects.requireNonNull(kind); Objects.requireNonNull(evidence); Objects.requireNonNull(price);
        Objects.requireNonNull(startsAt); Objects.requireNonNull(endsAt); Objects.requireNonNull(verifiedAt);
        if (contentRevision < 1) throw new IllegalArgumentException("Content revision must be positive.");
        if (startsAt.isPresent() && endsAt.isPresent() && !startsAt.get().isBefore(endsAt.get()))
            throw new IllegalArgumentException("Offer end must be after start.");
    }
}
