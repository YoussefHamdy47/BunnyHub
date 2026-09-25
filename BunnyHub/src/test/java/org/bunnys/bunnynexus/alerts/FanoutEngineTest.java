package org.bunnys.bunnynexus.alerts;

import org.bunnys.bunnynexus.alerts.application.FanoutEngine;
import org.bunnys.bunnynexus.alerts.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.bunnys.bunnynexus.alerts.AlertFixtures.*;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;

class FanoutEngineTest {
    private FanoutPlan plan() {
        return FanoutPlan.automatic("plan", "event", new FreeGameKey(OFFER_KEY), NOW)
                .claim("owner", NOW, NOW.plusSeconds(60));
    }
    private Subscription sub(String id, int channel, boolean enabled) {
        return new Subscription(id, new DestinationRef("100", Integer.toString(channel), "d" + channel, "i"),
                STORE, MARKET, Topic.FREE_GAME, true, true, enabled, NOW, 1, Set.of());
    }
    @Test void plansBoundedAudiencePagesAndCompletesOnlyAfterEmptyPage() {
        var p = plan(); var owner = p.lease().orElseThrow();
        var page = new FanoutEngine().plan(p, owner, List.of(sub("a", 200, true), sub("b", 201, false)), NOW);
        assertEquals(1, page.jobs().size()); assertEquals(1, page.skipped());
        var next = p.finishPage(owner, NOW, page.lastSubscriptionId(), 1, 0, 1);
        assertEquals(FanoutPlan.State.READY, next.state()); assertEquals(Optional.of("b"), next.afterSubscriptionId());
        var claimed = next.claim("next", NOW.plusSeconds(1), NOW.plusSeconds(60));
        var done = claimed.finishPage(claimed.lease().orElseThrow(), NOW.plusSeconds(1), Optional.empty(), 0, 0, 0);
        assertEquals(FanoutPlan.State.COMPLETE, done.state()); assertEquals(1, done.inserted()); assertEquals(1, done.skipped());
        assertThrows(IllegalStateException.class, () -> done.claim("w", NOW, NOW.plusSeconds(60)));
    }
    @Test void replayAndDifferentPlansUseSameEventChannelJobIdentity() {
        var first = plan();
        var second = FanoutPlan.automatic("different-plan", first.eventId(), first.notificationKey(), NOW)
                .claim("different-worker", NOW, NOW.plusSeconds(60));
        var engine = new FanoutEngine();
        var a = engine.plan(first, first.lease().orElseThrow(), List.of(sub("a", 200, true)), NOW).jobs().getFirst();
        var b = engine.plan(second, second.lease().orElseThrow(), List.of(sub("b", 200, true)), NOW.plusSeconds(1)).jobs().getFirst();
        assertEquals(a.id(), b.id()); assertEquals(a.key(), b.key());
        assertNotEquals(FanoutEngine.deliveryId(new DeliveryKey("event2", "200")), a.id());
    }
    @Test void staleOwnersCannotAdvanceAndCursorCannotMoveBackwards() {
        var old = plan(); var owner = old.lease().orElseThrow();
        var current = old.claim(owner.token(), NOW.plusSeconds(60), NOW.plusSeconds(120));
        assertThrows(IllegalStateException.class, () -> current.finishPage(owner, NOW.plusSeconds(60), Optional.of("a"), 1, 0, 0));
        var next = old.finishPage(owner, NOW, Optional.of("b"), 1, 0, 0).claim("n", NOW, NOW.plusSeconds(60));
        assertThrows(IllegalArgumentException.class, () -> next.finishPage(next.lease().orElseThrow(), NOW, Optional.of("a"), 1, 0, 0));
    }
    @Test void rejectsOversizedUnsortedOrDuplicateSubscriptionPages() {
        var p = plan(); var owner = p.lease().orElseThrow(); var engine = new FanoutEngine();
        assertThrows(IllegalArgumentException.class, () -> engine.plan(p, owner, Collections.nCopies(501, subscription()), NOW));
        assertThrows(IllegalArgumentException.class, () -> engine.plan(p, owner, List.of(sub("b", 200, true), sub("a", 201, true)), NOW));
        assertThrows(IllegalArgumentException.class, () -> engine.plan(p, owner, List.of(sub("a", 200, true), sub("a", 201, true)), NOW));
        assertThrows(IllegalArgumentException.class, () -> p.finishPage(owner, NOW, Optional.of("a"), 500, 1, 0));
        assertTrue(FanoutPlan.compareIds("\uE000", "\uD800\uDC00") < 0); // Mongo simple collation, unlike UTF-16 comparison
    }
    @Test void thousandGuildScenarioMaintainsBoundedPagesAndReconciledCounts() {
        var pending = FanoutPlan.automatic("p", "event", new FreeGameKey(OFFER_KEY), NOW);
        var jobs = new HashSet<String>();
        for (int offset = 0; offset < 1000; offset += 100) {
            var claimed = pending.claim("w", NOW, NOW.plusSeconds(60));
            List<Subscription> subscriptions = new ArrayList<>();
            for (int i = offset; i < offset + 100; i++) {
                var ref = new DestinationRef(Integer.toString(10000 + i), Integer.toString(20000 + i), "d" + i, "i");
                subscriptions.add(new Subscription(String.format(Locale.ROOT, "%04d", i), ref, STORE, MARKET,
                        Topic.FREE_GAME, true, true, true, NOW, 1, Set.of()));
            }
            var page = new FanoutEngine().plan(claimed, claimed.lease().orElseThrow(), subscriptions, NOW);
            assertEquals(100, page.jobs().size()); page.jobs().forEach(job -> assertTrue(jobs.add(job.id())));
            pending = claimed.finishPage(claimed.lease().orElseThrow(), NOW, page.lastSubscriptionId(), 100, 0, 0);
        }
        assertEquals(1000, pending.inserted()); assertEquals(1000, jobs.size());
    }
}
