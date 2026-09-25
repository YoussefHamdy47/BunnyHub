package org.bunnys.bunnynexus.alerts;

import org.bunnys.bunnynexus.alerts.domain.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.bunnys.bunnynexus.alerts.AlertFixtures.*;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;
import static org.bunnys.bunnynexus.alerts.domain.Offer.*;
import static org.bunnys.bunnynexus.alerts.domain.FreeGamePolicy.Decision.*;

class EligibilityTest {
    @Test void canonicalIdentityIgnoresSourceAndMetadataButDistinguishesOccurrenceAndMarket() {
        var first = offer();
        var fromOtherSource = new Offer(first.key(), first.editionId(), first.kind(),
                new Evidence(Proof.VERIFIED, Proof.VERIFIED, Proof.VERIFIED, Proof.VERIFIED,
                        new SourceId("itad"), "different-listing", "rule-v2"), first.price(), first.startsAt(),
                first.endsAt(), NOW.plusSeconds(1), 2);
        assertEquals(new FreeGameKey(first.key()), new FreeGameKey(fromOtherSource.key()));
        assertNotEquals(new FreeGameKey(first.key()), new FreeGameKey(new OfferKey(STORE, "campaign-2", MARKET)));
        assertNotEquals(first.key(), new OfferKey(STORE, "campaign-1", new Market("US")));
        assertNotEquals(first.key(), new OfferKey(new StoreId("gog"), "campaign-1", MARKET));
        assertNotEquals(new DigestKey("d", "i", LocalDate.now(), "daily"), new FreeGameKey(first.key()));
    }
    @Test void validatesUnambiguousBoundedIdsAndExactMoney() {
        assertThrows(IllegalArgumentException.class, () -> new StoreId(" epic"));
        assertThrows(IllegalArgumentException.class, () -> new Market(""));
        assertThrows(IllegalArgumentException.class, () -> new SourceId("invalid\uD800"));
        assertThrows(IllegalArgumentException.class, () -> new SourceId("x".repeat(257)));
        assertThrows(IllegalArgumentException.class, () -> new DeliveryKey("e", "18446744073709551616"));
        assertEquals("18446744073709551615", snowflake("18446744073709551615"));
        assertThrows(IllegalArgumentException.class, () -> new Money(-1, Currency.getInstance("USD")));
        var o = offer();
        var oneMinorUnit = with(o.kind(), o.evidence(), Optional.of(new Money(1, Currency.getInstance("JPY"))),
                o.startsAt(), o.endsAt(), NOW);
        assertEquals(INELIGIBLE, POLICY.evaluate(oneMinorUnit, NOW).decision());
        assertEquals(ELIGIBLE, POLICY.evaluate(o, NOW).decision());
    }
    @Test void trialsDlcPaidAndPermanentFreeAreNotGiveaways() {
        var o = offer();
        for (Kind kind : Kind.values()) {
            if (kind == Kind.FREE_TO_KEEP_BASE_GAME) continue;
            assertFalse(POLICY.evaluate(with(kind, o.evidence(), o.price(), o.startsAt(), o.endsAt(), NOW), NOW).eligible());
        }
    }
    @Test void missingOrRejectedEvidenceCannotBecomeEligible() {
        var o = offer();
        for (int missing = 0; missing < 4; missing++) {
            for (Proof proof : List.of(Proof.UNKNOWN, Proof.REJECTED)) {
                Proof[] p = {Proof.VERIFIED, Proof.VERIFIED, Proof.VERIFIED, Proof.VERIFIED};
                p[missing] = proof;
                var e = new Evidence(p[0], p[1], p[2], p[3], new SourceId("source"), "item", "rule");
                assertEquals(proof == Proof.UNKNOWN ? UNKNOWN : INELIGIBLE,
                        POLICY.evaluate(with(o.kind(), e, o.price(), o.startsAt(), o.endsAt(), NOW), NOW).decision());
            }
        }
        assertEquals(UNKNOWN, POLICY.evaluate(with(o.kind(), o.evidence(), Optional.empty(), o.startsAt(), o.endsAt(), NOW), NOW).decision());
    }
    @Test void temporalBoundariesAreInclusiveAtStartExclusiveAtEndAndFreshnessDeadline() {
        var o = offer();
        assertEquals(SCHEDULED, POLICY.evaluate(o, NOW.minusSeconds(61)).decision());
        var atStart = with(o.kind(), o.evidence(), o.price(), Optional.of(NOW), o.endsAt(), NOW);
        assertEquals(ELIGIBLE, POLICY.evaluate(atStart, NOW).decision());
        assertEquals(STALE, POLICY.evaluate(o, NOW.plusSeconds(600)).decision());
        assertEquals(EXPIRED, POLICY.evaluate(o, o.endsAt().orElseThrow()).decision());
        assertEquals(UNKNOWN, POLICY.evaluate(with(o.kind(), o.evidence(), o.price(), o.startsAt(), o.endsAt(), NOW.plusSeconds(1)), NOW).decision());
        assertEquals(NOW.plusSeconds(600), POLICY.validUntil(o));
    }
    @Test void unknownDatesRequireExplicitPolicyAndFreshEvidence() {
        var o = offer();
        var unknownEnd = with(o.kind(), o.evidence(), o.price(), o.startsAt(), Optional.empty(), NOW);
        assertEquals(UNKNOWN, POLICY.evaluate(unknownEnd, NOW).decision());
        var freshUnknownEndPolicy = new FreeGamePolicy(Duration.ofMinutes(10), true);
        assertTrue(freshUnknownEndPolicy.evaluate(unknownEnd, NOW).eligible());
        assertFalse(freshUnknownEndPolicy.evaluate(unknownEnd, NOW.plusSeconds(600)).eligible());
        assertEquals(UNKNOWN, POLICY.evaluate(with(o.kind(), o.evidence(), o.price(), Optional.empty(), o.endsAt(), NOW), NOW).decision());
        assertThrows(IllegalArgumentException.class, () -> with(o.kind(), o.evidence(), o.price(), Optional.of(NOW), Optional.of(NOW), NOW));
        assertThrows(IllegalArgumentException.class, () -> new FreeGamePolicy(Duration.ZERO, true));
    }
    @Test void automaticAudienceChecksOwnershipIncarnationTopicAndActivation() {
        var s = subscription();
        assertEquals(Subscription.Match.MATCH, s.matchAutomatic(s.id(), DESTINATION, OFFER_KEY, Topic.FREE_GAME, NOW));
        assertEquals(Subscription.Match.ACTIVATED_AFTER_EVENT, s.matchAutomatic(s.id(), DESTINATION, OFFER_KEY, Topic.FREE_GAME, NOW.minusSeconds(1)));
        assertEquals(Subscription.Match.WRONG_TOPIC, s.matchAutomatic(s.id(), DESTINATION, OFFER_KEY, Topic.GOOD_DEAL, NOW));
        assertEquals(Subscription.Match.WRONG_MARKET, s.matchAutomatic(s.id(), DESTINATION, new OfferKey(STORE, "campaign-1", new Market("US")), Topic.FREE_GAME, NOW));
        for (DestinationRef different : List.of(new DestinationRef("101", "200", "destination", "incarnation-1"),
                new DestinationRef("100", "200", "destination", "incarnation-2")))
            assertEquals(Subscription.Match.REPLACED_DESTINATION, s.matchAutomatic(s.id(), different, OFFER_KEY, Topic.FREE_GAME, NOW));
    }
    @Test void rolesAreImmutableBoundedAndNeverEveryone() {
        var roles = new HashSet<>(Set.of("300"));
        var s = new Subscription("s", DESTINATION, STORE, MARKET, Topic.FREE_GAME, true, true, true, NOW, 1, roles);
        roles.add("400");
        assertEquals(Set.of("300"), s.roleIds());
        assertThrows(UnsupportedOperationException.class, () -> s.roleIds().add("400"));
        assertThrows(IllegalArgumentException.class, () -> Subscription.checkedRoles(Set.of("100"), "100"));
        Set<String> tooMany = new HashSet<>();
        for (int i = 1000; i < 1101; i++) tooMany.add(Integer.toString(i));
        assertThrows(IllegalArgumentException.class, () -> Subscription.checkedRoles(tooMany, "100"));
    }
}
