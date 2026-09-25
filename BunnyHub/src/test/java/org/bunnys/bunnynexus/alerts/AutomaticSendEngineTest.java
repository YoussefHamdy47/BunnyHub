package org.bunnys.bunnynexus.alerts;

import org.bunnys.bunnynexus.alerts.application.AutomaticSendEngine;
import org.bunnys.bunnynexus.alerts.domain.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.bunnys.bunnynexus.alerts.AlertFixtures.*;
import static org.bunnys.bunnynexus.alerts.application.AutomaticSendEngine.*;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;

class AutomaticSendEngineTest {
    private final AutomaticSendEngine engine = new AutomaticSendEngine(POLICY);
    private PreparedPayload payload() {
        return new PreparedPayload(OFFER_KEY, subscription().id(), DESTINATION, "attempt-1", "stable-job-nonce", 1, 1, "v1", HASH, Set.of("300"), HASH);
    }
    private Snapshot snapshot(Subscription s, Offer o, Instant now, boolean permissions) {
        return new Snapshot(leased(), s, o, "event", new FreeGameKey(OFFER_KEY), NOW, now, permissions);
    }
    private Decision authorize(Snapshot snapshot, PreparedPayload p) {
        return engine.authorize(snapshot, snapshot.job().lease().orElseThrow(), p);
    }
    @Test void freezesCurrentRolesRevisionAndFreshnessDeadlineWithoutSideEffects() {
        var snapshot = snapshot(subscription(), offer(), NOW, true);
        var result = assertInstanceOf(Sending.class, authorize(snapshot, payload()));
        var attempt = result.job().attempt().orElseThrow();
        assertEquals(Set.of("300"), attempt.roleIds());
        assertEquals(HASH, attempt.payloadHash());
        assertEquals(snapshot.job().lease().orElseThrow().until(), attempt.validUntil());
        assertEquals(DeliveryJob.State.LEASED, snapshot.job().state());
    }
    @Test void changedRolesOrContentRequiresNewRendering() {
        var s = subscription();
        var changed = new Subscription(s.id(), s.destination(), s.store(), s.market(), s.topic(), true, true, true,
                s.enabledSince(), 2, Set.of("400"));
        assertEquals(new Blocked(BlockReason.REFRESH_PAYLOAD), authorize(snapshot(changed, offer(), NOW, true), payload()));
        var o = offer();
        var revised = new Offer(o.key(), o.editionId(), o.kind(), o.evidence(), o.price(), o.startsAt(), o.endsAt(), NOW, 2);
        assertEquals(new Blocked(BlockReason.REFRESH_PAYLOAD), authorize(snapshot(s, revised, NOW, true), payload()));
        var extraRole = new PreparedPayload(OFFER_KEY, s.id(), DESTINATION, "a", "n", 1, 1, "v1", HASH, Set.of("300", "400"), HASH);
        assertEquals(new Blocked(BlockReason.REFRESH_PAYLOAD), authorize(snapshot(s, o, NOW, true), extraRole));
    }
    @Test void earlierPermissionAndOfferDeadlinesStillWinOverTheJobLease() {
        var permission = new Snapshot(leased(), subscription(), offer(), "event", new FreeGameKey(OFFER_KEY),
                NOW, NOW, true, NOW.plusSeconds(5));
        assertEquals(NOW.plusSeconds(5), assertInstanceOf(Sending.class, authorize(permission, payload())).job().attempt().orElseThrow().validUntil());
        var o = offer();
        var expiring = with(o.kind(), o.evidence(), o.price(), o.startsAt(), java.util.Optional.of(NOW.plusSeconds(2)), NOW);
        assertEquals(NOW.plusSeconds(2), assertInstanceOf(Sending.class, authorize(snapshot(subscription(), expiring, NOW, true), payload()))
                .job().attempt().orElseThrow().validUntil());
    }
    @Test void disablingAnyConfigurationLevelSkipsUnsentWork() {
        var s = subscription();
        for (int disabled = 0; disabled < 3; disabled++) {
            var changed = new Subscription(s.id(), s.destination(), s.store(), s.market(), s.topic(),
                    disabled != 0, disabled != 1, disabled != 2, s.enabledSince(), 2, s.roleIds());
            var result = assertInstanceOf(Skipped.class, authorize(snapshot(changed, offer(), NOW, true), payload()));
            assertEquals(DeliveryJob.Reason.CONFIGURATION_CHANGED, result.job().reason());
        }
    }
    @Test void replacedDestinationAndPaidOnlySubscriptionCannotAuthorizeFreeAlert() {
        var s = subscription();
        var replaced = new Subscription(s.id(), new DestinationRef("100", "200", "destination", "new-incarnation"),
                STORE, MARKET, Topic.FREE_GAME, true, true, true, NOW, 2, s.roleIds());
        assertInstanceOf(Skipped.class, authorize(snapshot(replaced, offer(), NOW, true), payload()));
        var paid = new Subscription(s.id(), DESTINATION, STORE, MARKET, Topic.GOOD_DEAL, true, true, true, NOW, 1, s.roleIds());
        assertInstanceOf(Skipped.class, authorize(snapshot(paid, offer(), NOW, true), payload()));
    }
    @Test void missingPermissionsAndStaleEvidenceBlockRatherThanSend() {
        assertEquals(new Blocked(BlockReason.REPAIR_PERMISSIONS), authorize(snapshot(subscription(), offer(), NOW, false), payload()));
        var o = offer();
        var stale = with(o.kind(), o.evidence(), o.price(), o.startsAt(), o.endsAt(), NOW.minusSeconds(600));
        assertEquals(new Blocked(BlockReason.REFRESH_EVIDENCE), authorize(snapshot(subscription(), stale, NOW, true), payload()));
    }
    @Test void expiredOfferSkipsAndExpiredOwnerCannotAuthorize() {
        var o = offer();
        var expired = with(o.kind(), o.evidence(), o.price(), o.startsAt(), java.util.Optional.of(NOW), NOW);
        var result = assertInstanceOf(Skipped.class, authorize(snapshot(subscription(), expired, NOW, true), payload()));
        assertEquals(DeliveryJob.Reason.OFFER_INELIGIBLE, result.job().reason());
        assertThrows(IllegalStateException.class, () -> authorize(snapshot(subscription(), o, NOW.plusSeconds(30), true), payload()));
    }
    @Test void mismatchedEventCannotSupplyEvidenceForAnotherJob() {
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(leased(), subscription(), offer(), "other-event",
                new FreeGameKey(OFFER_KEY), NOW, NOW, true));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(leased(), subscription(), offer(), "event",
                new FreeGameKey(new OfferKey(STORE, "other-campaign", MARKET)), NOW, NOW, true));
    }
    @Test void equalRevisionsAndRolesDoNotAuthorizeAnotherOfferSubscriptionOrDestination() {
        var s = subscription();
        var foreignOffer = new OfferKey(STORE, "different-game", MARKET);
        var foreignDestination = new DestinationRef("100", "201", "different-destination", "incarnation-1");
        var reincarnated = new DestinationRef("100", "200", "destination", "incarnation-2");
        for (var p : java.util.List.of(
                new PreparedPayload(foreignOffer, s.id(), DESTINATION, "a", "n", 1, 1, "v1", HASH, s.roleIds(), HASH),
                new PreparedPayload(OFFER_KEY, "different-subscription", DESTINATION, "a", "n", 1, 1, "v1", HASH, s.roleIds(), HASH),
                new PreparedPayload(OFFER_KEY, s.id(), foreignDestination, "a", "n", 1, 1, "v1", HASH, s.roleIds(), HASH),
                new PreparedPayload(OFFER_KEY, s.id(), reincarnated, "a", "n", 1, 1, "v1", HASH, s.roleIds(), HASH))) {
            assertEquals(new Blocked(BlockReason.REFRESH_PAYLOAD), authorize(snapshot(s, offer(), NOW, true), p));
        }
    }
}

