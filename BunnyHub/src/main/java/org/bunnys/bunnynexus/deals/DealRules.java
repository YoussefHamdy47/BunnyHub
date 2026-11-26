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
     * Rating x review popularity x discount. Logarithmic review counts reward broad appeal without letting
     * raw counts dominate. Normalize by each store's review floor because GOG has far fewer reviews.
     * Price only breaks a relevance tie; IDs make equal deals stable across source ordering changes.
     */
    static double score(Deal deal) {
        double rating = deal.rating() / (deal.store() == DealStore.STEAM ? 100.0 : 50.0);
        double reviews = (double) deal.reviews() / (deal.store() == DealStore.STEAM ? MIN_STEAM_REVIEWS : MIN_GOG_REVIEWS);
        return rating * Math.log1p(reviews) * deal.percentOff() / 100.0;
    }

    static List<Deal> byRelevance(List<Deal> deals) {
        return deals.stream().sorted(Comparator.comparingDouble(DealRules::score).reversed()
                .thenComparing(Comparator.comparingInt(Deal::percentOff).reversed())
                .thenComparingLong(Deal::saleCents).thenComparing(Deal::id)).toList();
    }
}
