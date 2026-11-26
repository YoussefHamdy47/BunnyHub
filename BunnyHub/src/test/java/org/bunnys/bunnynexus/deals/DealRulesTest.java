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
        var steam = kept.stream().filter(d -> d.store() == DealStore.STEAM).toList();
        var titles = steam.stream().map(Deal::title).toList();
        assertTrue(titles.indexOf("Peggle Deluxe") > 0, "lowest price no longer leads the list");
        assertTrue(steam.getFirst().reviews() > steam.stream().filter(d -> d.title().equals("Peggle Deluxe")).findFirst().orElseThrow().reviews());
        assertEquals(kept.size(), kept.stream().map(Deal::id).distinct().count(), "a game on both Steam pages appears once");
    }

    @Test void aCheaperLaterRowOfTheSameGameWins() {
        var rows = List.of(DealFixtures.deal("steam:1", DealStore.STEAM, 700, 6999), DealFixtures.deal("steam:1", DealStore.STEAM, 699, 6999));
        assertEquals(699, DealRules.rank(rows).getFirst().saleCents());
    }

    static Deal candidate(String id, DealStore store, int rating, int reviews, int discount, long sale) {
        return new Deal(id, store, id, URI.create("https://example.com/game"), sale,
                sale * 100 / (100 - discount), discount, rating, reviews, Optional.empty());
    }

    @Test void ratingReviewCountAndDiscountEachImproveRanking() {
        var base = candidate("base", DealStore.STEAM, 90, 10000, 80, 99);
        for (var better : List.of(
                candidate("rating", DealStore.STEAM, 98, 10000, 80, 999),
                candidate("reviews", DealStore.STEAM, 90, 100000, 80, 999),
                candidate("discount", DealStore.STEAM, 90, 10000, 90, 999))) {
            assertEquals(better, DealRules.rank(List.of(base, better)).getFirst());
        }
    }

    @Test void theBestCandidateIsKeptEvenWhenItArrivesAfterTheLimit() {
        var rows = new java.util.ArrayList<Deal>();
        for (int i = 0; i < 65; i++)
            rows.add(candidate("ordinary:" + i, DealStore.STEAM, 85, 1000, 75, 99));
        var best = candidate("best", DealStore.STEAM, 98, 100000, 90, 999);
        rows.add(best);
        var ranked = DealRules.rank(rows);
        assertEquals(60, ranked.size());
        assertEquals(best, ranked.getFirst());
        java.util.Collections.reverse(rows);
        assertEquals(ranked, DealRules.rank(rows), "source ordering must not choose the winners");
    }

    @Test void storesUseComparableRatingAndReviewScalesAndPriceOnlyBreaksTies() {
        var steam = candidate("steam", DealStore.STEAM, 90, 10000, 80, 999);
        var gog = candidate("gog", DealStore.GOG, 45, 1000, 80, 999);
        assertEquals(DealRules.score(steam), DealRules.score(gog), 0.000001);
        var cheap = candidate("cheap", DealStore.STEAM, 90, 10000, 80, 99);
        assertEquals(cheap, DealRules.rank(List.of(steam, cheap)).getFirst());
        assertEquals(cheap, new DealSnapshot(List.of(steam, cheap), DealFixtures.FETCHED, 2).deals().getFirst(),
                "cached snapshots adopt the ranking immediately");
    }
}