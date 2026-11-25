package org.bunnys.bunnynexus.deals;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DealRefreshTest {
    final Instant now = Instant.parse("2026-11-23T15:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    /** Answers from the captured responses, or fails as told; counts requests. */
    static final class Source implements DealRefresh.Source {
        DealFeed.Fetch steamFailure, gogFailure;
        int requests;
        @Override public DealFeed.Fetch steamSearch(String order) {
            requests++;
            if (steamFailure != null) return steamFailure;
            try { return new DealFeed.Ok(DealFixtures.steamSearch(order.startsWith("filter") ? "topsellers" : "reviews")); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        List<Long> askedFor = List.of();
        @Override public DealFeed.Fetch steamItems(List<Long> appIds) {
            requests++;
            askedFor = appIds;
            try { return new DealFeed.Ok(DealFixtures.steamItemsFor(appIds)); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        @Override public DealFeed.Fetch gog() {
            requests++;
            if (gogFailure != null) return gogFailure;
            try { return new DealFeed.Ok(DealFixtures.gog()); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
    }

    final Source source = new Source();
    final AtomicReference<DealSnapshot> saved = new AtomicReference<>(), published = new AtomicReference<>(DealSnapshot.EMPTY);
    final DealRefresh refresh = new DealRefresh(source, saved::set, clock, published::get, published::set);

    @Test void aGoodRefreshUsesFourRequestsAndPublishes() {
        assertEquals(DealRefresh.EVERY, refresh.run());
        assertEquals(4, source.requests, "two Steam searches, one Steam item request, one GOG page");
        assertEquals(33, source.askedFor.size(), "only the 33 search rows showing 75% off or more are looked up");
        var snapshot = published.get();
        assertEquals(58, snapshot.deals().size());
        assertEquals(now, snapshot.fetchedAt());
        assertSame(snapshot, saved.get());
        for (int i = 1; i < snapshot.deals().size(); i++)
            assertTrue(snapshot.deals().get(i - 1).saleCents() <= snapshot.deals().get(i).saleCents(), "cheapest first");
    }

    @Test void whenOneStoreFailsItsPreviousDealsAreKept() {
        refresh.run();
        source.gogFailure = new DealFeed.Failed("HTTP 503", Optional.empty());
        refresh.run();
        var snapshot = published.get();
        assertEquals(33, snapshot.forStore(Optional.of(DealStore.GOG)).size(), "GOG deals from the last refresh");
        assertEquals(25, snapshot.forStore(Optional.of(DealStore.STEAM)).size());
    }

    @Test void whenEveryStoreFailsTheOldSnapshotStaysAndItBacksOff() {
        source.steamFailure = new DealFeed.Failed("network: SocketTimeoutException", Optional.empty());
        source.gogFailure = new DealFeed.Failed("HTTP 500", Optional.empty());
        assertEquals(Duration.ofMinutes(30), refresh.run());
        assertEquals(Duration.ofHours(1), refresh.run());
        assertEquals(Duration.ofHours(3), refresh.run());
        assertSame(DealSnapshot.EMPTY, published.get());
        assertNull(saved.get());
    }

    @Test void rateLimitsWaitAtLeastThreeHours() {
        source.steamFailure = new DealFeed.Failed("HTTP 429", Optional.of(Duration.ofHours(5)));
        source.gogFailure = new DealFeed.Failed("HTTP 429", Optional.of(Duration.ofSeconds(120)));
        assertEquals(Duration.ofHours(5), refresh.run());
        source.steamFailure = new DealFeed.Failed("HTTP 429", Optional.of(Duration.ofSeconds(60)));
        assertEquals(Duration.ofHours(3), refresh.run());
    }

    @Test void aFreshSavedSnapshotMeansNoRequestOnStart() {
        var fresh = new DealSnapshot(List.of(), now.minus(Duration.ofHours(1)), 60);
        assertEquals(Duration.ofHours(2), DealRefresh.firstDelay(Optional.of(fresh), now));
        assertEquals(Duration.ZERO, DealRefresh.firstDelay(Optional.of(new DealSnapshot(List.of(), now.minus(Duration.ofHours(4)), 60)), now));
        assertEquals(DealRefresh.FIRST_WITHOUT_SNAPSHOT, DealRefresh.firstDelay(Optional.empty(), now));
    }
}
