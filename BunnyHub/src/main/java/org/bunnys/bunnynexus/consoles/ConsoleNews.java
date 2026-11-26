package org.bunnys.bunnynexus.consoles;

import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSubscriptions.Subscription;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Finds new console news and posts it to the channels whose Console section has the matching choice ticked, pinging
 * that setting's role:
 * <ul>
 *   <li>PS Plus monthly games and Game Catalog: each new PlayStation Blog post, once, if under a week old.</li>
 *   <li>Game Pass: the first check only records what the lists hold; after that, games new on a list wait and go out
 *       together, at most one post a day. Free Play Days weekends go to the same channels, once each.</li>
 *   <li>Console in-game items: like Game Pass, the first check records what is listed and new items go out at most
 *       once a day.</li>
 * </ul>
 * Each post reaches each channel at most once, and is offered for {@link #OPEN} (so a channel that turns it on
 * meanwhile still gets it). Not thread-safe: one thread runs it.
 */
final class ConsoleNews {
    static final Duration RECENT = Duration.ofDays(7), DAILY = Duration.ofHours(20), OPEN = Duration.ofDays(3),
            WEEKEND_MAX_AGE = Duration.ofDays(3), FORGET = Duration.ofDays(60);
    static final int TRIES = 3, SEEN_KEPT = 200;

    interface Source {
        List<PsPlusPost> psPlus() throws IOException;
        List<String> shelf(GamePass.Shelf shelf) throws IOException;
        List<GamePass.Game> games(List<String> ids) throws IOException;
        List<FreeWeekend> freeWeekends() throws IOException;
        List<ConsoleItem> items() throws IOException;
    }

    /**
     * What has been seen. {@code seen}: per Game Pass list, each game's last sighting; {@code pending}: games new on a
     * list, not posted yet; {@code seeded}: the lists were read once. The {@code items} fields work the same way.
     */
    record State(List<String> psSeen, Map<GamePass.Shelf, Map<String, Instant>> seen, Map<GamePass.Shelf, List<String>> pending,
                 Optional<Instant> lastGamePassPost, boolean seeded, boolean migrated, List<String> weekendsSeen,
                 Map<String, Instant> itemsSeen, List<String> itemsPending, Optional<Instant> lastItemsPost, boolean itemsSeeded) {
        static final State EMPTY = new State(List.of(), Map.of(), Map.of(), Optional.empty(), false, false, List.of(), Map.of(),
                List.of(), Optional.empty(), false);

        State withPsSeen(List<String> ids) {
            return new State(ids, seen, pending, lastGamePassPost, seeded, migrated, weekendsSeen, itemsSeen, itemsPending, lastItemsPost, itemsSeeded);
        }
        State withGamePass(Map<GamePass.Shelf, Map<String, Instant>> seen, Map<GamePass.Shelf, List<String>> pending, Optional<Instant> last) {
            return new State(psSeen, seen, pending, last, true, migrated, weekendsSeen, itemsSeen, itemsPending, lastItemsPost, itemsSeeded);
        }
        State withMigrated() {
            return new State(psSeen, seen, pending, lastGamePassPost, seeded, true, weekendsSeen, itemsSeen, itemsPending, lastItemsPost, itemsSeeded);
        }
        State withWeekendsSeen(List<String> ids) {
            return new State(psSeen, seen, pending, lastGamePassPost, seeded, migrated, ids, itemsSeen, itemsPending, lastItemsPost, itemsSeeded);
        }
        State withItems(Map<String, Instant> seen, List<String> pending, Optional<Instant> last) {
            return new State(psSeen, this.seen, this.pending, lastGamePassPost, this.seeded, migrated, weekendsSeen, seen, pending, last, true);
        }
    }

    /**
     * One post: exactly one of {@code psPlus}, {@code gamePass} (non-empty), {@code weekend} or {@code items}
     * (non-empty). {@code delivered}: channels it was sent to (or is being sent to); {@code failures}: failed tries per
     * channel.
     */
    record Post(String id, FreebieStore store, Instant createdAt, Optional<PsPlusPost> psPlus,
                Map<GamePass.Shelf, List<GamePass.Game>> gamePass, Optional<FreeWeekend> weekend, List<ConsoleItem> items,
                Set<String> delivered, Map<String, Integer> failures) {
        static Post of(String id, FreebieStore store, Instant at, PsPlusPost ps) {
            return new Post(id, store, at, Optional.of(ps), Map.of(), Optional.empty(), List.of(), Set.of(), Map.of());
        }
        static Post of(String id, FreebieStore store, Instant at, Map<GamePass.Shelf, List<GamePass.Game>> games) {
            return new Post(id, store, at, Optional.empty(), games, Optional.empty(), List.of(), Set.of(), Map.of());
        }
        static Post of(String id, FreebieStore store, Instant at, FreeWeekend weekend) {
            return new Post(id, store, at, Optional.empty(), Map.of(), Optional.of(weekend), List.of(), Set.of(), Map.of());
        }
        static Post of(String id, FreebieStore store, Instant at, List<ConsoleItem> items) {
            return new Post(id, store, at, Optional.empty(), Map.of(), Optional.empty(), items, Set.of(), Map.of());
        }
        Post with(Set<String> delivered, Map<String, Integer> failures) {
            return new Post(id, store, createdAt, psPlus, gamePass, weekend, items, delivered, failures);
        }
        MessageCreateData message(Optional<String> role) {
            if (psPlus.isPresent()) return ConsoleNewsCards.psPlus(psPlus.get(), role);
            if (weekend.isPresent()) return ConsoleNewsCards.freeWeekend(weekend.get(), role);
            if (!items.isEmpty()) return ConsoleNewsCards.items(items, role);
            return ConsoleNewsCards.gamePass(gamePass, role);
        }
    }

    interface Store {
        State state();
        void save(State state);
        /** Inserts the post unless one with its ID exists. */
        void create(Post post);
        List<Post> open();
        /** Marks the channel as delivered unless it already is; true when this call claimed it. */
        boolean claim(String postId, String channelId);
        /** The send failed: the channel is unclaimed and its failure counted. */
        void failed(String postId, String channelId);
        void close(String postId);
    }

    interface Discord {
        boolean ready();
        /** @return an error to log, or empty when sent */
        Optional<String> send(String guildId, String channelId, MessageCreateData message);
    }

    private final Source source;
    private final Store store;
    private final Discord discord;
    private final Function<FreebieStore, Iterable<Subscription>> audience;
    private final BooleanSupplier paused;

    ConsoleNews(Source source, Store store, Discord discord, Function<FreebieStore, Iterable<Subscription>> audience, BooleanSupplier paused) {
        this.source = source; this.store = store; this.discord = discord; this.audience = audience; this.paused = paused;
    }

    private interface Step { State run(State state, Instant now) throws IOException; }

    /** Checks every source (a failure of one leaves the others working), then delivers. */
    void run(Instant now) {
        var state = store.state();
        state = step("PS Plus", this::psPlus, state, now);
        state = step("Game Pass", this::gamePass, state, now);
        state = step("Free Play Days", this::freeWeekends, state, now);
        state = step("In-game items", this::items, state, now);
        store.save(state);
        deliver(now);
    }

    private static State step(String what, Step step, State state, Instant now) {
        try { return step.run(state, now); }
        catch (IOException | RuntimeException failure) {
            BunnyLog.warning("[Console] " + what + " check failed | " + FailureDiagnostics.describe(failure));
            return state;
        }
    }

    State psPlus(State state, Instant now) throws IOException {
        var seen = once(state.psSeen(), source.psPlus(), PsPlusPost::id, PsPlusPost::published, RECENT, now,
                post -> {
                    store.create(Post.of(post.id(), FreebieStore.PS_PLUS, now, post));
                    BunnyLog.success("[Console] New: " + post.heading());
                });
        return state.withPsSeen(seen);
    }

    State freeWeekends(State state, Instant now) throws IOException {
        var seen = once(state.weekendsSeen(), source.freeWeekends(), FreeWeekend::id, FreeWeekend::published, WEEKEND_MAX_AGE, now,
                weekend -> {
                    store.create(Post.of(weekend.id(), FreebieStore.GAME_PASS, now, weekend));
                    BunnyLog.success("[Console] New: Free Play Days " + weekend.dates());
                });
        return state.withWeekendsSeen(seen);
    }

    /** Posts each entry not seen before that is at most {@code maxAge} old; returns the IDs seen, newest kept. */
    private static <T> List<String> once(List<String> before, List<T> entries, Function<T, String> id, Function<T, Instant> published,
                                         Duration maxAge, Instant now, java.util.function.Consumer<T> post) {
        List<String> seen = new ArrayList<>(before);
        for (T entry : entries.reversed()) {
            if (seen.contains(id.apply(entry))) continue;
            seen.add(id.apply(entry));
            Instant at = published.apply(entry);
            if (at.isAfter(now.minus(maxAge)) && !at.isAfter(now.plus(Duration.ofDays(1)))) post.accept(entry);
        }
        return List.copyOf(seen.size() > SEEN_KEPT ? seen.subList(seen.size() - SEEN_KEPT, seen.size()) : seen);
    }

    State gamePass(State state, Instant now) throws IOException {
        Map<GamePass.Shelf, List<String>> current = new EnumMap<>(GamePass.Shelf.class);
        for (GamePass.Shelf shelf : GamePass.Shelf.values()) current.put(shelf, source.shelf(shelf));

        Map<GamePass.Shelf, Map<String, Instant>> seen = new EnumMap<>(GamePass.Shelf.class);
        Map<GamePass.Shelf, List<String>> pending = new EnumMap<>(GamePass.Shelf.class);
        for (GamePass.Shelf shelf : GamePass.Shelf.values()) {
            var diff = diff(state.seen().getOrDefault(shelf, Map.of()), state.pending().getOrDefault(shelf, List.of()),
                    current.get(shelf), state.seeded(), now);
            seen.put(shelf, diff.seen());
            pending.put(shelf, diff.pending());
        }
        if (!state.seeded()) {
            BunnyLog.success("[Console] Game Pass lists recorded; changes post from now on.");
            return state.withGamePass(seen, pending, state.lastGamePassPost());
        }
        if (!due(state.lastGamePassPost(), now) || pending.values().stream().allMatch(List::isEmpty))
            return state.withGamePass(seen, pending, state.lastGamePassPost());

        List<String> ids = pending.values().stream().flatMap(List::stream).distinct().toList();
        Map<String, GamePass.Game> byId = new HashMap<>();
        for (GamePass.Game game : source.games(ids)) byId.put(game.id(), game);
        Map<GamePass.Shelf, List<GamePass.Game>> shown = new EnumMap<>(GamePass.Shelf.class);
        Map<GamePass.Shelf, List<String>> none = new EnumMap<>(GamePass.Shelf.class);
        for (var entry : pending.entrySet()) {
            none.put(entry.getKey(), List.of());
            // PC-only games are left out: this goes to console channels.
            List<GamePass.Game> games = entry.getValue().stream().map(byId::get).filter(Objects::nonNull).filter(GamePass.Game::console).toList();
            if (!games.isEmpty()) shown.put(entry.getKey(), games);
        }
        if (shown.isEmpty()) return state.withGamePass(seen, none, state.lastGamePassPost());
        store.create(Post.of("gamepass:" + now.getEpochSecond(), FreebieStore.GAME_PASS, now, shown));
        BunnyLog.success("[Console] New Game Pass update: " + shown.values().stream().mapToInt(List::size).sum() + " game(s)");
        return state.withGamePass(seen, none, Optional.of(now));
    }

    State items(State state, Instant now) throws IOException {
        var listed = source.items();
        Map<String, ConsoleItem> byId = new LinkedHashMap<>();
        for (ConsoleItem item : listed) byId.putIfAbsent(item.id(), item);
        var diff = diff(state.itemsSeen(), state.itemsPending(), List.copyOf(byId.keySet()), state.itemsSeeded(), now);
        if (!state.itemsSeeded()) {
            BunnyLog.success("[Console] In-game items recorded (" + byId.size() + "); new ones post from now on.");
            return state.withItems(diff.seen(), diff.pending(), state.lastItemsPost());
        }
        if (!due(state.lastItemsPost(), now) || diff.pending().isEmpty()) return state.withItems(diff.seen(), diff.pending(), state.lastItemsPost());
        // Already ended items are dropped rather than posted.
        var items = diff.pending().stream().map(byId::get).filter(Objects::nonNull)
                .filter(i -> i.ends().map(end -> end.isAfter(now)).orElse(true)).toList();
        if (items.isEmpty()) return state.withItems(diff.seen(), List.of(), state.lastItemsPost());
        store.create(Post.of("items:" + now.getEpochSecond(), FreebieStore.CONSOLE_ITEMS, now, items));
        BunnyLog.success("[Console] New in-game items: " + items.size());
        return state.withItems(diff.seen(), List.of(), Optional.of(now));
    }

    private static boolean due(Optional<Instant> last, Instant now) {
        return last.map(at -> !now.isBefore(at.plus(DAILY))).orElse(true);
    }

    private record Diff(Map<String, Instant> seen, List<String> pending) {}

    /**
     * One list read against what was seen before: entries still listed stay pending, entries new since the last read
     * (once the list has been read before) are added, and sightings older than {@link #FORGET} are forgotten.
     */
    private static Diff diff(Map<String, Instant> before, List<String> wasPending, List<String> current, boolean seeded, Instant now) {
        List<String> pending = new ArrayList<>();
        // Still listed: a game that moved from "coming" to "added" is posted once, as added.
        for (String id : wasPending) if (current.contains(id)) pending.add(id);
        if (seeded) for (String id : current) if (!before.containsKey(id) && !pending.contains(id)) pending.add(id);
        Map<String, Instant> seen = new HashMap<>();
        before.forEach((id, at) -> { if (at.isAfter(now.minus(FORGET))) seen.put(id, at); });
        for (String id : current) seen.put(id, now);
        return new Diff(seen, List.copyOf(pending));
    }

    /** Sends each open post to every channel that wants it and does not have it yet. */
    void deliver(Instant now) {
        if (!discord.ready() || paused.getAsBoolean()) return;
        for (Post post : store.open()) {
            if (now.isAfter(post.createdAt().plus(OPEN))) { store.close(post.id()); continue; }
            int sent = 0;
            for (Subscription s : audience.apply(post.store())) {
                if (post.delivered().contains(s.channelId()) || post.failures().getOrDefault(s.channelId(), 0) >= TRIES) continue;
                if (!store.claim(post.id(), s.channelId())) continue;
                var error = discord.send(s.guildId(), s.channelId(), post.message(s.roleId()));
                if (error.isPresent()) {
                    store.failed(post.id(), s.channelId());
                    BunnyLog.warning("[Console] Post to " + s.guildId() + " failed: " + error.get());
                } else sent++;
            }
            if (sent > 0) BunnyLog.success("[Console] Delivered " + post.id() + " to " + sent + " channel(s)");
        }
    }
}
