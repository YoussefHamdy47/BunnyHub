package org.bunnys.bunnynexus.deals;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DealRulesTest {
    static Deal with(DealStore store, int off, int rating, int reviews, long sale, long normal) {
        return new Deal(store.choice + ":1", store, "A", URI.create(store == DealStore.GOG ? "https://www.gog.com/en/game/a" : "https://store.steampowered.com/app/1/"),
                sale, normal, off, rating, reviews, Optional.empty());
    }

    @Test void eachThresholdAtItsEdge() {
        assertTrue(DealRules.qualifies(with(DealStore.STEAM, 75, 85, 1000, 100, 400)));
        assertFalse(DealRules.qualifies(with(DealStore.STEAM, 74, 85, 1000, 100, 400)));
        assertFalse(DealRules.qualifies(with(DealStore.STEAM, 75, 84, 1000, 100, 400)));
        assertFalse(DealRules.qualifies(with(DealStore.STEAM, 75, 85, 999, 100, 400)));
        assertFalse(DealRules.qualifies(with(DealStore.STEAM, 100, 85, 1000, 0, 400)), "free games belong to /free-games");
        assertFalse(DealRules.qualifies(with(DealStore.STEAM, 75, 85, 1000, 400, 400)));
        assertTrue(DealRules.qualifies(with(DealStore.GOG, 75, 43, 100, 100, 400)));
        assertFalse(DealRules.qualifies(with(DealStore.GOG, 75, 42, 100, 100, 400)), "below 4.3 stars");
        assertFalse(DealRules.qualifies(with(DealStore.GOG, 75, 43, 99, 100, 400)));
    }

    @Test void theCapturedResponsesRankAsDocumented() throws Exception {
        var rows = DealFixtures.rows();
        assertEquals(296, rows.size(), "196 Steam games on sale and 100 GOG products");
        var kept = DealRules.rank(rows);
        assertEquals(25, kept.stream().filter(d -> d.store() == DealStore.STEAM).count());
        assertEquals(33, kept.stream().filter(d -> d.store() == DealStore.GOG).count());
        assertEquals(List.of("steam:3480", "steam:3390", "gog:1207658880", "gog:1207658918", "gog:1207658916", "gog:1207659230"),
                kept.subList(0, 6).stream().map(Deal::id).toList(), "cheapest first; equal price keeps the bigger discount, then the order read");
        for (int i = 1; i < kept.size(); i++) assertTrue(kept.get(i - 1).saleCents() <= kept.get(i).saleCents());
        assertEquals(kept.size(), kept.stream().map(Deal::id).distinct().count(), "a game on both Steam pages appears once");
    }

    @Test void aCheaperLaterRowOfTheSameGameWins() {
        var rows = List.of(DealFixtures.deal("steam:1", DealStore.STEAM, 700, 6999), DealFixtures.deal("steam:1", DealStore.STEAM, 699, 6999));
        assertEquals(699, DealRules.rank(rows).getFirst().saleCents());
    }

    @Test void byPriceIsCheapestFirstThenTheBiggerDiscount() {
        var deals = List.of(DealFixtures.deal("steam:1", DealStore.STEAM, 500, 2000), DealFixtures.deal("steam:2", DealStore.STEAM, 199, 999),
                DealFixtures.deal("gog:3", DealStore.GOG, 199, 3999));
        assertEquals(List.of("gog:3", "steam:2", "steam:1"), DealRules.byPrice(deals).stream().map(Deal::id).toList());
    }
}
