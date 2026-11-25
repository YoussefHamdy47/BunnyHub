package org.bunnys.bunnynexus.deals;

import org.bunnys.utils.BunnyLog;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One refresh (docs/deals/02-architecture.md): Steam (two search pages, then item details for their games) and GOG
 * (one catalog page), at most five requests. The good deals are saved and published. When one store fails, its deals
 * from the previous refresh are kept (while fresh); when both fail, the previous snapshot stays.
 */
final class DealRefresh {
    static final Duration EVERY = Duration.ofHours(3), FIRST_WITHOUT_SNAPSHOT = Duration.ofSeconds(15);
    static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(3));

    /** The stores' endpoints; {@link DealFeed} in production. */
    interface Source {
        DealFeed.Fetch steamSearch(String order);
        DealFeed.Fetch steamItems(List<Long> appIds);
        DealFeed.Fetch gog();
    }

    /** What a refresh writes; {@link DealRepository} in production. */
    interface Store {
        void saveSnapshot(DealSnapshot snapshot);
    }

    private final Source source;
    private final Store store;
    private final Clock clock;
    private final Supplier<DealSnapshot> previous;
    private final Consumer<DealSnapshot> publish;
    private int failures;
    /** The longest Retry-After seen in this refresh. */
    private Optional<Duration> rateLimited = Optional.empty();

    DealRefresh(Source source, Store store, Clock clock, Supplier<DealSnapshot> previous, Consumer<DealSnapshot> publish) {
        this.source = source; this.store = store; this.clock = clock; this.previous = previous; this.publish = publish;
    }

    /** No request on start while the saved snapshot is younger than {@link #EVERY}. */
    static Duration firstDelay(Optional<DealSnapshot> saved, Instant now) {
        if (saved.isEmpty() || saved.get().fetchedAt().equals(Instant.EPOCH)) return FIRST_WITHOUT_SNAPSHOT;
        Duration left = Duration.between(now, saved.get().fetchedAt().plus(EVERY));
        return left.isNegative() ? Duration.ZERO : left;
    }

    /** One refresh; returns the delay until the next one. */
    Duration run() {
        long started = System.nanoTime();
        rateLimited = Optional.empty();
        Instant now = clock.instant();
        var steam = steam();
        var gog = read("GOG", source.gog(), DealIntake::gog);
        if (steam.isEmpty() && gog.isEmpty()) return failed();
        var before = previous.get();
        List<Deal> rows = new ArrayList<>();
        rows.addAll(steam.orElseGet(() -> kept(before, DealStore.STEAM, now)));
        rows.addAll(gog.orElseGet(() -> kept(before, DealStore.GOG, now)));
        var snapshot = new DealSnapshot(DealRules.rank(rows), now, rows.size());
        store.saveSnapshot(snapshot);
        publish.accept(snapshot);
        failures = 0;
        BunnyLog.info("[Deals] Refreshed: " + snapshot.deals().size() + " good deals (" + rows.size() + " on sale) in "
                + Duration.ofNanos(System.nanoTime() - started).toMillis() + " ms");
        return rateLimited.filter(wait -> wait.compareTo(EVERY) > 0).orElse(EVERY);
    }

    /**
     * Both Steam search pages, then one details request for the games on them that show a big enough discount
     * (at most 100, in page order). Empty when Steam failed.
     */
    private Optional<List<Deal>> steam() {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        boolean searched = false;
        for (String order : DealFeed.STEAM_ORDERS) {
            var page = read("Steam search", source.steamSearch(order), body -> DealIntake.steamSearch(body, DealRules.MIN_PERCENT_OFF));
            if (page.isEmpty()) continue;
            searched = true;
            for (long id : page.get()) if (ids.size() < DealIntake.MAX_ROWS) ids.add(id);
        }
        if (!searched) return Optional.empty();
        if (ids.isEmpty()) return Optional.of(List.of());
        return read("Steam items", source.steamItems(List.copyOf(ids)), DealIntake::steamItems);
    }

    /** A store's deals from the previous refresh, while that one is fresh. */
    private static List<Deal> kept(DealSnapshot before, DealStore store, Instant now) {
        return before.stale(now) ? List.of() : before.forStore(Optional.of(store));
    }

    private interface Reader<T> { T read(byte[] body) throws DealIntake.Invalid; }

    private <T> Optional<T> read(String what, DealFeed.Fetch fetch, Reader<T> reader) {
        switch (fetch) {
            case DealFeed.Failed failed -> {
                failed.retryAfter().ifPresent(wait -> rateLimited = Optional.of(rateLimited.filter(w -> w.compareTo(wait) > 0).orElse(wait)));
                BunnyLog.warning("[Deals] " + what + " failed: " + failed.reason());
                return Optional.empty();
            }
            case DealFeed.Ok ok -> {
                try { return Optional.of(reader.read(ok.body())); }
                catch (DealIntake.Invalid invalid) {
                    BunnyLog.warning("[Deals] " + what + " unusable: " + invalid.getMessage());
                    return Optional.empty();
                }
            }
        }
    }

    private Duration failed() {
        failures++;
        Duration next = rateLimited.map(wait -> wait.compareTo(EVERY) > 0 ? wait : EVERY)
                .orElse(BACKOFF.get(Math.min(failures, BACKOFF.size()) - 1));
        BunnyLog.warning("[Deals] Refresh failed for every store; next try in " + human(next));
        return next;
    }

    private static String human(Duration duration) {
        return duration.toMinutes() >= 60 && duration.toMinutesPart() == 0 ? duration.toHours() + "h" : duration.toMinutes() + " min";
    }
}
