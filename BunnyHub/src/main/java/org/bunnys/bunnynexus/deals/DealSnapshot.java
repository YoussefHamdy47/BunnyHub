package org.bunnys.bunnynexus.deals;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The good deals of the last refresh, top deals first. Immutable; replaced as a whole. */
public record DealSnapshot(List<Deal> deals, Instant fetchedAt, int rowsSeen, Set<String> lowest) {
    /** Older than this, /deals says deals are unavailable and boards are left alone. */
    public static final Duration MAX_AGE = Duration.ofHours(24);
    public static final DealSnapshot EMPTY = new DealSnapshot(List.of(), Instant.EPOCH, 0);

    public DealSnapshot { deals = DealRules.byRelevance(deals); lowest = Set.copyOf(lowest); }

    /** Without price history: no deal is marked as the lowest price seen. */
    public DealSnapshot(List<Deal> deals, Instant fetchedAt, int rowsSeen) { this(deals, fetchedAt, rowsSeen, Set.of()); }

    /** The same deals with these marked as at their lowest tracked price ({@link DealPrices}). */
    public DealSnapshot withLowest(Set<String> ids) { return new DealSnapshot(deals, fetchedAt, rowsSeen, ids); }

    public boolean lowest(Deal deal) { return lowest.contains(deal.id()); }

    /** True before the first refresh, or when the last good one is more than a day old. */
    public boolean stale(Instant now) { return fetchedAt.equals(Instant.EPOCH) || fetchedAt.plus(MAX_AGE).isBefore(now); }

    public List<Deal> forStore(Optional<DealStore> store) {
        return store.map(s -> deals.stream().filter(d -> d.store() == s).toList()).orElse(deals);
    }
}
