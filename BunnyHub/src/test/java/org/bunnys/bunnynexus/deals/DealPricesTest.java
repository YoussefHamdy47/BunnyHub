package org.bunnys.bunnynexus.deals;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DealPricesTest {
    final Instant now = Instant.parse("2026-12-20T12:00:00Z");

    static final class Memory implements DealPrices.Store {
        final Map<String, DealPrices.Seen> seen = new HashMap<>();
        @Override public Map<String, DealPrices.Seen> seen(Collection<String> ids) {
            Map<String, DealPrices.Seen> found = new HashMap<>();
            ids.forEach(id -> { if (seen.containsKey(id)) found.put(id, seen.get(id)); });
            return found;
        }
        @Override public void record(List<Deal> deals, Map<String, DealPrices.Seen> before, Instant at) {
            for (Deal d : deals) seen.merge(d.id(), new DealPrices.Seen(d.saleCents(), at),
                    (old, fresh) -> new DealPrices.Seen(Math.min(old.lowCents(), fresh.lowCents()), old.firstSeen()));
        }
    }

    @Test void onlyAGameTrackedForTwoWeeksAtItsLowestPriceGetsTheBadge() {
        var store = new Memory();
        var game = DealFixtures.deal("steam:1", DealStore.STEAM, 499, 1999);
        assertTrue(DealPrices.update(store, List.of(game), now.minus(Duration.ofDays(20))).isEmpty(), "first sighting");
        var dearer = DealFixtures.deal("steam:1", DealStore.STEAM, 799, 1999);
        assertTrue(DealPrices.update(store, List.of(dearer), now).isEmpty(), "dearer than the lowest seen");
        assertEquals(Set.of("steam:1"), DealPrices.update(store, List.of(game), now), "back at its lowest, tracked 20 days");
        var cheaper = DealFixtures.deal("steam:1", DealStore.STEAM, 299, 1999);
        assertEquals(Set.of("steam:1"), DealPrices.update(store, List.of(cheaper), now), "a new low");
        assertEquals(299, store.seen.get("steam:1").lowCents());

        var fresh = DealFixtures.deal("gog:2", DealStore.GOG, 99, 999);
        DealPrices.update(store, List.of(fresh), now.minus(Duration.ofDays(3)));
        assertTrue(DealPrices.update(store, List.of(fresh), now).isEmpty(), "three days of history is not enough");
    }

    @Test void theBadgeShowsOnTheListAndTheBoard() throws Exception {
        var snapshot = DealFixtures.snapshot();
        var top = snapshot.forStore(Optional.of(DealStore.STEAM)).getFirst();
        var marked = snapshot.withLowest(Set.of(top.id()));
        assertTrue(DealCards.boardCard(marked, DealCards.Notice.NONE).plainText().contains(DealCards.LOWEST));
        assertTrue(DealCards.listCard(marked, Optional.empty(), 0).plainText().contains(DealCards.LOWEST));
        assertFalse(DealCards.boardCard(snapshot, DealCards.Notice.NONE).plainText().contains(DealCards.LOWEST));
        assertNotEquals(DealBoard.View.of(snapshot).key(), DealBoard.View.of(marked).key(), "a new badge edits the board");
    }
}
