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
        /** The console stores; a source without them (older tests) reads Steam and GOG only. */
        default DealFeed.Fetch xbox(Optional<String> next) { return new DealFeed.Failed("not read", Optional.empty()); }
        default DealFeed.Fetch playstation() { return new DealFeed.Failed("not read", Optional.empty()); }
        default DealFeed.Fetch playstationGame(String productId) { return new DealFeed.Failed("not read", Optional.empty()); }
        default DealFeed.Fetch nintendo() { return new DealFeed.Failed("not read", Optional.empty()); }
        /** Steam players' rating of a game by its title: percent positive and review count. Empty: not on Steam, or unknown. */
        default Optional<SteamScore> steamRating(String title, Optional<String> publisher) { return Optional.empty(); }
        /** False for sources without the console stores, so their absence is not logged as a failure. */
        default boolean consoles() { return false; }
    }

    record SteamScore(int percent, int reviews) {}

    /** Ratings read from a game's page (PlayStation) or Steam (Nintendo) are kept this long before being read again. */
    static final Duration RATING_KEPT = Duration.ofDays(3);
    /** New game pages and Steam lookups per refresh, so one refresh stays a few requests per store. */
    static final int NEW_RATINGS_PER_REFRESH = 20;
    private record Rated<T>(Optional<T> rating, Instant at) {}
    private final Map<String, Rated<DealConsoleIntake.PlayStationRating>> playstationRatings = new HashMap<>();
    private final Map<String, Rated<SteamScore>> nintendoRatings = new HashMap<>();

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
        Optional<List<Deal>> xbox = Optional.empty(), playstation = Optional.empty(), nintendo = Optional.empty();
        // Console sales run for days and their pages are large (about 1 MB a check), so the consoles are read every
        // CONSOLES_EVERY; in between, their deals from the last read are kept.
        if (source.consoles() && (lastConsoles == null || !now.isBefore(lastConsoles.plus(CONSOLES_EVERY)))) {
            xbox = xbox(); playstation = playstation(now); nintendo = nintendo(now);
            if (xbox.isPresent() || playstation.isPresent() || nintendo.isPresent()) lastConsoles = now;
        }
        if (steam.isEmpty() && gog.isEmpty() && xbox.isEmpty() && playstation.isEmpty() && nintendo.isEmpty()) return failed();
        var before = previous.get();
        List<Deal> rows = new ArrayList<>();
        rows.addAll(steam.orElseGet(() -> kept(before, DealStore.STEAM, now)));
        rows.addAll(gog.orElseGet(() -> kept(before, DealStore.GOG, now)));
        rows.addAll(xbox.orElseGet(() -> kept(before, DealStore.XBOX, now)));
        rows.addAll(playstation.orElseGet(() -> kept(before, DealStore.PLAYSTATION, now)));
        rows.addAll(nintendo.orElseGet(() -> kept(before, DealStore.NINTENDO, now)));
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

    /** The most popular Xbox console games on sale, {@link DealFeed#XBOX_PAGES} pages. Empty when the first page failed. */
    private Optional<List<Deal>> xbox() {
        List<Deal> deals = new ArrayList<>();
        Optional<String> next = Optional.empty();
        for (int page = 0; page < DealFeed.XBOX_PAGES; page++) {
            var read = read("Xbox", source.xbox(next), DealConsoleIntake::xbox);
            if (read.isEmpty()) return page == 0 ? Optional.empty() : Optional.of(deals);
            deals.addAll(read.get().deals());
            next = read.get().next();
            if (next.isEmpty()) break;
        }
        return Optional.of(deals);
    }

    /**
     * PlayStation's best-selling deals. Only games that already pass the discount bar get their store page read, for
     * the star rating and sale end; a page is read again after {@link #RATING_KEPT}.
     */
    private Optional<List<Deal>> playstation(Instant now) {
        var rows = read("PlayStation", source.playstation(), DealConsoleIntake::playstation);
        if (rows.isEmpty()) return Optional.empty();
        playstationRatings.values().removeIf(r -> r.at().plus(RATING_KEPT).isBefore(now));
        List<Deal> deals = new ArrayList<>();
        int fresh = 0;
        for (var row : rows.get()) {
            if (row.percentOff() < DealRules.MIN_PERCENT_OFF) continue;
            var known = playstationRatings.get(row.productId());
            if (known == null) {
                if (fresh++ >= NEW_RATINGS_PER_REFRESH) continue; // The rest wait for the next refresh.
                var page = source.playstationGame(row.productId());
                if (!(page instanceof DealFeed.Ok ok)) continue; // Asked again next refresh.
                known = new Rated<>(DealConsoleIntake.playstationRating(ok.body()), now);
                playstationRatings.put(row.productId(), known);
            }
            known.rating().ifPresent(r -> deals.add(row.withRating(r)));
        }
        return Optional.of(deals);
    }

    /** Nintendo's featured deals, rated by Steam players of the same game (looked up once per {@link #RATING_KEPT}). */
    private Optional<List<Deal>> nintendo(Instant now) {
        var rows = read("Nintendo", source.nintendo(), DealConsoleIntake::nintendo);
        if (rows.isEmpty()) return Optional.empty();
        nintendoRatings.values().removeIf(r -> r.at().plus(RATING_KEPT).isBefore(now));
        List<Deal> deals = new ArrayList<>();
        int fresh = 0;
        for (var row : rows.get()) {
            if (row.percentOff() < DealRules.MIN_PERCENT_OFF) continue;
            var known = nintendoRatings.get(row.nsuid());
            if (known == null) {
                if (fresh++ >= NEW_RATINGS_PER_REFRESH) continue;
                known = new Rated<>(source.steamRating(steamTitle(row.title()), row.publisher()), now);
                nintendoRatings.put(row.nsuid(), known);
            }
            known.rating().ifPresent(r -> deals.add(row.withRating(r.percent(), r.reviews())));
        }
        return Optional.of(deals);
    }

    /** The eShop's title without its marks and Switch edition suffix, as Steam lists the game. */
    static String steamTitle(String eshop) {
        return eshop.replaceAll("[®™©]", "").replaceAll("(?i)\\s*[–-]\\s*Nintendo Switch(?:™)?\\s*2?\\s*Edition.*$", "")
                .replaceAll("(?i)\\s+for Nintendo Switch$", "").replaceAll("\\s+", " ").strip();
    }

    /** A store's deals from the previous refresh, while that one is fresh; a sale that has ended is dropped. */
    private static List<Deal> kept(DealSnapshot before, DealStore store, Instant now) {
        return before.stale(now) ? List.of() : before.forStore(Optional.of(store)).stream()
                .filter(d -> d.endsAt().map(now::isBefore).orElse(true)).toList();
    }

    /** How often the console stores are read; Steam and GOG are read every {@link #EVERY}. */
    static final Duration CONSOLES_EVERY = Duration.ofHours(9);
    private Instant lastConsoles;

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
