package org.bunnys.bunnynexus.deals;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What counts as a good deal, and the order deals are shown in. Pure: the same rows always give the same list.
 * Free games are never deals (they belong to /free-games).
 */
final class DealRules {
    static final int MIN_PERCENT_OFF = 75, MAX_KEPT = 60;
    /** Steam: percent positive and review count. */
    static final int MIN_STEAM_RATING = 85, MIN_STEAM_REVIEWS = 1000;
    /** GOG: stars in tenths (43 = 4.3 of 5) and review count; GOG has far fewer reviews than Steam. */
    static final int MIN_GOG_RATING = 43, MIN_GOG_REVIEWS = 100;

    private DealRules() {}

    static boolean qualifies(Deal deal) {
        if (deal.saleCents() <= 0 || deal.saleCents() >= deal.normalCents() || deal.percentOff() < MIN_PERCENT_OFF) return false;
        return switch (deal.store()) {
            case STEAM -> deal.rating() >= MIN_STEAM_RATING && deal.reviews() >= MIN_STEAM_REVIEWS;
            case GOG -> deal.rating() >= MIN_GOG_RATING && deal.reviews() >= MIN_GOG_REVIEWS;
        };
    }

    /**
     * Qualifying rows, one per game per store (a later, cheaper row replaces the earlier one).
     * Rank every candidate before keeping the best {@link #MAX_KEPT}.
     */
    static List<Deal> rank(List<Deal> rows) {
        Map<String, Deal> games = new LinkedHashMap<>();
        for (Deal row : rows) {
            if (!qualifies(row)) continue;
            Deal shown = games.get(row.id());
            if (shown == null) {
                games.put(row.id(), row);
            } else if (row.saleCents() < shown.saleCents()) {
                games.put(row.id(), row);
            }
        }
        return byRelevance(List.copyOf(games.values())).stream().limit(MAX_KEPT).toList();
    }

    /**
     * How sure the store's reviews make us that the game is good: the lower bound of the 95% Wilson interval of its share
     * of positive reviews (Steam's percent positive, GOG's stars out of 5). Few reviews pull it down: 5 stars from
     * 2 reviews scores about 0.34, 4.7 stars from 100,000 about 0.94. Same scale for both stores.
     */
    static double score(Deal deal) {
        double positive = deal.rating() / (deal.store() == DealStore.STEAM ? 100.0 : 50.0);
        double n = deal.reviews();
        if (n <= 0) return 0;
        double z = 1.96, z2 = z * z;
        double centre = positive + z2 / (2 * n);
        double spread = z * Math.sqrt(positive * (1 - positive) / n + z2 / (4 * n * n));
        return Math.max(0, (centre - spread) / (1 + z2 / n));
    }

    /** {@link #score} in whole points: games within a point of each other count as equally rated. */
    static int ratingPoints(Deal deal) { return (int) Math.floor(score(deal) * 100 + 1e-9); }

    /**
     * Best first: confidence-adjusted rating (by the point), then the bigger discount, then the lower price after the
     * discount. The exact score and the ID only settle what is left, so the order is stable whatever order the stores
     * list games in.
     */
    static final Comparator<Deal> ORDER = Comparator.comparingInt(DealRules::ratingPoints).reversed()
            .thenComparing(Comparator.comparingInt(Deal::percentOff).reversed())
            .thenComparingLong(Deal::saleCents)
            .thenComparing(Comparator.comparingDouble(DealRules::score).reversed())
            .thenComparing(Deal::id);

    static List<Deal> byRelevance(List<Deal> deals) {
        return deals.stream().sorted(ORDER).toList();
    }
}
