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
     * Qualifying rows, one per game per store (the store's own order decides which are kept; a later, cheaper row of
     * the same game replaces the earlier one), at most {@link #MAX_KEPT}, then cheapest first.
     */
    static List<Deal> rank(List<Deal> rows) {
        Map<String, Deal> games = new LinkedHashMap<>();
        for (Deal row : rows) {
            if (!qualifies(row)) continue;
            Deal shown = games.get(row.id());
            if (shown == null) {
                if (games.size() < MAX_KEPT) games.put(row.id(), row);
            } else if (row.saleCents() < shown.saleCents()) {
                games.put(row.id(), row);
            }
        }
        return byPrice(List.copyOf(games.values()));
    }

    /** Cheapest first; on equal price the bigger discount first, then the order they came in. */
    static List<Deal> byPrice(List<Deal> deals) {
        return deals.stream().sorted(Comparator.comparingLong(Deal::saleCents).thenComparing(d -> -d.percentOff())).toList();
    }
}
