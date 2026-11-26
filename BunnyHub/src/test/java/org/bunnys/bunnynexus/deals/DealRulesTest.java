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
        assertEquals("TOEM: A Photo Adventure", titles.getFirst(), "99% positive from 10K reviews, 90% off");
        // Same rating point (97% from thousands of reviews): the bigger discount, then the lower price.
        assertTrue(titles.indexOf("Peggle Deluxe") < titles.indexOf("Gorogoa"), "80% off before 75% off");
        assertTrue(titles.indexOf("Euro Truck Simulator 2") < titles.indexOf("South Park™: The Stick of Truth™"), "$4.99 before $7.49");
        for (int i = 1; i < kept.size(); i++) assertTrue(DealRules.ORDER.compare(kept.get(i - 1), kept.get(i)) <= 0);
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
        var base = candidate("base", DealStore.STEAM, 90, 1000, 80, 99);
        for (var better : List.of(
                candidate("rating", DealStore.STEAM, 98, 1000, 80, 999),
                candidate("reviews", DealStore.STEAM, 90, 100000, 80, 999),
                candidate("discount", DealStore.STEAM, 90, 1000, 90, 999))) {
            assertEquals(better, DealRules.rank(List.of(base, better)).getFirst());
        }
    }

    @Test void fewReviewsCountForLessThanManyGoodOnes() {
        // 5 stars from 2 reviews against 4.7 stars from 100,000.
        var two = candidate("two", DealStore.GOG, 50, 2, 90, 99);
        var many = candidate("many", DealStore.GOG, 47, 100000, 75, 999);
        assertTrue(DealRules.score(two) < 0.4, String.valueOf(DealRules.score(two)));
        assertEquals(93, DealRules.ratingPoints(many));
        assertEquals(List.of(many, two), DealRules.byRelevance(List.of(two, many)), "rating by review count comes before discount and price");
    }

    @Test void thenTheBiggerDiscountThenTheLowerPrice() {
        // The same rating from the same number of reviews: discount decides, then the price after it.
        var cheap75 = candidate("cheap75", DealStore.STEAM, 95, 50000, 75, 99);
        var dear90 = candidate("dear90", DealStore.STEAM, 95, 50000, 90, 1999);
        var cheap90 = candidate("cheap90", DealStore.STEAM, 95, 50000, 90, 499);
        assertEquals(List.of(cheap90, dear90, cheap75), DealRules.byRelevance(List.of(cheap75, dear90, cheap90)));
        // A better-rated game still leads a cheaper, bigger discount.
        var better = candidate("better", DealStore.STEAM, 98, 50000, 75, 2999);
        assertEquals(better, DealRules.byRelevance(List.of(cheap90, better)).getFirst());
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
        var gog = candidate("gog", DealStore.GOG, 45, 10000, 80, 999);
        assertEquals(DealRules.score(steam), DealRules.score(gog), 0.000001);
        var cheap = candidate("cheap", DealStore.STEAM, 90, 10000, 80, 99);
        assertEquals(cheap, DealRules.rank(List.of(steam, cheap)).getFirst());
        assertEquals(cheap, new DealSnapshot(List.of(steam, cheap), DealFixtures.FETCHED, 2).deals().getFirst(),
                "cached snapshots adopt the ranking immediately");
    }
}