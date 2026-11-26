package org.bunnys.bunnynexus.deals;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The good deals of the last refresh, top deals first. Immutable; replaced as a whole. */
public record DealSnapshot(List<Deal> deals, Instant fetchedAt, int rowsSeen) {
    /** Older than this, /deals says deals are unavailable and boards are left alone. */
    public static final Duration MAX_AGE = Duration.ofHours(24);
    public static final DealSnapshot EMPTY = new DealSnapshot(List.of(), Instant.EPOCH, 0);

    public DealSnapshot { deals = DealRules.byRelevance(deals); }

    /** True before the first refresh, or when the last good one is more than a day old. */
    public boolean stale(Instant now) { return fetchedAt.equals(Instant.EPOCH) || fetchedAt.plus(MAX_AGE).isBefore(now); }

    public List<Deal> forStore(Optional<DealStore> store) {
        return store.map(s -> deals.stream().filter(d -> d.store() == s).toList()).orElse(deals);
    }
}
