package org.bunnys.bunnynexus.alerts;

import org.bunnys.bunnynexus.alerts.domain.*;
import java.time.*;
import java.util.*;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;
import static org.bunnys.bunnynexus.alerts.domain.Offer.*;

final class AlertFixtures {
    static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
    static final StoreId STORE = new StoreId("epic");
    static final Market MARKET = new Market("EG");
    static final OfferKey OFFER_KEY = new OfferKey(STORE, "campaign-1", MARKET);
    static final DestinationRef DESTINATION = new DestinationRef("100", "200", "destination", "incarnation-1");
    static final FreeGamePolicy POLICY = new FreeGamePolicy(Duration.ofMinutes(10), false);
    static final String HASH = "a".repeat(64);
    static Offer offer() {
        return new Offer(OFFER_KEY, "edition-1", Kind.FREE_TO_KEEP_BASE_GAME,
                new Evidence(Proof.VERIFIED, Proof.VERIFIED, Proof.VERIFIED, Proof.VERIFIED,
                        new SourceId("gamerpower"), "listing-1", "certified-rule-v1"),
                Optional.of(new Money(0, Currency.getInstance("EGP"))), Optional.of(NOW.minusSeconds(60)),
                Optional.of(NOW.plusSeconds(3600)), NOW, 1);
    }
    static Offer with(Kind kind, Evidence evidence, Optional<Money> price, Optional<Instant> start,
                      Optional<Instant> end, Instant verified) {
        return new Offer(OFFER_KEY, "edition-1", kind, evidence, price, start, end, verified, 1);
    }
    static Subscription subscription() {
        return new Subscription("subscription", DESTINATION, STORE, MARKET, Topic.FREE_GAME,
                true, true, true, NOW, 1, Set.of("300"));
    }
    static DeliveryJob ready() {
        return DeliveryJob.ready("job", new DeliveryKey("event", "200"), DESTINATION, "subscription", NOW);
    }
    static DeliveryJob leased() { return ready().claim("worker-1", NOW, NOW.plusSeconds(30)); }
    static DeliveryJob.Attempt attempt(String id, int ordinal, Instant now) {
        return new DeliveryJob.Attempt(id, ordinal, "stable-job-nonce", 1, 1, "v1", HASH,
                Set.of("300"), now, now.plusSeconds(20));
    }
    static DeliveryJob sending() {
        var job = leased();
        return job.authorize(job.lease().orElseThrow(), NOW, attempt("attempt-1", 1, NOW));
    }
}
