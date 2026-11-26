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
 * Finds new PlayStation Plus and Game Pass news and posts it to the channels whose Console section has "PS Plus monthly
 * games" or "Xbox Game Pass updates" ticked, pinging that setting's role.
 *
 * <p>PS Plus: each new blog post about the monthly games or the Game Catalog, once, if it is under a week old. Game
 * Pass: the first check only records what the lists hold; after that, games new on a list wait, and go out together
 * in at most one post a day. Each post reaches each channel at most once, and is offered for {@link #OPEN} (so a
 * channel that turns it on meanwhile still gets it). Not thread-safe: one thread runs it.
 */
final class ConsoleNews {
    static final Duration PS_MAX_AGE = Duration.ofDays(7), GAME_PASS_GAP = Duration.ofHours(20), OPEN = Duration.ofDays(3),
            FORGET = Duration.ofDays(60);
    static final int TRIES = 3, PS_SEEN_KEPT = 200;

    interface Source {
        List<PsPlusPost> psPlus() throws IOException;
        List<String> shelf(GamePass.Shelf shelf) throws IOException;
        List<GamePass.Game> games(List<String> ids) throws IOException;
    }

    /**
     * What has been seen. {@code seen}: per Game Pass list, each game's last sighting. {@code pending}: games new on a
     * list, not posted yet. {@code seeded}: the lists were read once.
     */
    record State(List<String> psSeen, Map<GamePass.Shelf, Map<String, Instant>> seen, Map<GamePass.Shelf, List<String>> pending,
                 Optional<Instant> lastGamePassPost, boolean seeded, boolean migrated) {
        static final State EMPTY = new State(List.of(), Map.of(), Map.of(), Optional.empty(), false, false);
    }

    /** One post. {@code delivered}: channels it was sent to (or is being sent to); {@code failures}: failed tries per channel. */
    record Post(String id, FreebieStore store, Instant createdAt, Optional<PsPlusPost> psPlus,
                Map<GamePass.Shelf, List<GamePass.Game>> gamePass, Set<String> delivered, Map<String, Integer> failures) {}

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

    /** Checks both sources (a failure of one leaves the other working), then delivers. */
    void run(Instant now) {
        var state = store.state();
        try { state = psPlus(state, now); }
        catch (IOException | RuntimeException failure) { BunnyLog.warning("[Console] PS Plus check failed | " + FailureDiagnostics.describe(failure)); }
        try { state = gamePass(state, now); }
        catch (IOException | RuntimeException failure) { BunnyLog.warning("[Console] Game Pass check failed | " + FailureDiagnostics.describe(failure)); }
        store.save(state);
        deliver(now);
    }

    State psPlus(State state, Instant now) throws IOException {
        var posts = source.psPlus();
        List<String> seen = new ArrayList<>(state.psSeen());
        for (PsPlusPost post : posts.reversed()) {
            if (seen.contains(post.id())) continue;
            seen.add(post.id());
            if (post.published().isAfter(now.minus(PS_MAX_AGE)) && !post.published().isAfter(now.plus(Duration.ofDays(1)))) {
                store.create(new Post(post.id(), FreebieStore.PS_PLUS, now, Optional.of(post), Map.of(), Set.of(), Map.of()));
                BunnyLog.success("[Console] New: " + post.heading());
            }
        }
        if (seen.size() > PS_SEEN_KEPT) seen = seen.subList(seen.size() - PS_SEEN_KEPT, seen.size());
        return new State(List.copyOf(seen), state.seen(), state.pending(), state.lastGamePassPost(), state.seeded(), state.migrated());
    }

    State gamePass(State state, Instant now) throws IOException {
        Map<GamePass.Shelf, List<String>> current = new EnumMap<>(GamePass.Shelf.class);
        for (GamePass.Shelf shelf : GamePass.Shelf.values()) current.put(shelf, source.shelf(shelf));

        Map<GamePass.Shelf, Map<String, Instant>> seen = new EnumMap<>(GamePass.Shelf.class);
        Map<GamePass.Shelf, List<String>> pending = new EnumMap<>(GamePass.Shelf.class);
        for (GamePass.Shelf shelf : GamePass.Shelf.values()) {
            Map<String, Instant> before = state.seen().getOrDefault(shelf, Map.of());
            List<String> waiting = new ArrayList<>();
            // Still on the list, and new to it since it was last read: a game that moved from "coming" to "added" is
            // posted once, as added.
            for (String id : state.pending().getOrDefault(shelf, List.of())) if (current.get(shelf).contains(id)) waiting.add(id);
            if (state.seeded()) for (String id : current.get(shelf)) if (!before.containsKey(id) && !waiting.contains(id)) waiting.add(id);
            Map<String, Instant> after = new HashMap<>();
            before.forEach((id, at) -> { if (at.isAfter(now.minus(FORGET))) after.put(id, at); });
            for (String id : current.get(shelf)) after.put(id, now);
            seen.put(shelf, after);
            pending.put(shelf, waiting);
        }
        var next = new State(state.psSeen(), seen, pending, state.lastGamePassPost(), true, state.migrated());
        if (!state.seeded()) { BunnyLog.success("[Console] Game Pass lists recorded; changes post from now on."); return next; }

        boolean due = state.lastGamePassPost().map(last -> !now.isBefore(last.plus(GAME_PASS_GAP))).orElse(true);
        if (!due || pending.values().stream().allMatch(List::isEmpty)) return next;

        List<String> ids = pending.values().stream().flatMap(List::stream).distinct().toList();
        Map<String, GamePass.Game> byId = new HashMap<>();
        for (GamePass.Game game : source.games(ids)) byId.put(game.id(), game);
        Map<GamePass.Shelf, List<GamePass.Game>> shown = new EnumMap<>(GamePass.Shelf.class);
        for (var entry : pending.entrySet()) {
            List<GamePass.Game> games = entry.getValue().stream().map(byId::get).filter(Objects::nonNull).filter(GamePass.Game::console).toList();
            if (!games.isEmpty()) shown.put(entry.getKey(), games);
        }
        Map<GamePass.Shelf, List<String>> none = new EnumMap<>(GamePass.Shelf.class);
        for (GamePass.Shelf shelf : GamePass.Shelf.values()) none.put(shelf, List.of());
        if (shown.isEmpty()) return new State(next.psSeen(), seen, none, state.lastGamePassPost(), true, state.migrated());
        String id = "gamepass:" + now.getEpochSecond();
        store.create(new Post(id, FreebieStore.GAME_PASS, now, Optional.empty(), shown, Set.of(), Map.of()));
        BunnyLog.success("[Console] New Game Pass update: " + shown.values().stream().mapToInt(List::size).sum() + " game(s)");
        return new State(next.psSeen(), seen, none, Optional.of(now), true, state.migrated());
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
                var message = post.psPlus().map(ps -> ConsoleNewsCards.psPlus(ps, s.roleId()))
                        .orElseGet(() -> ConsoleNewsCards.gamePass(post.gamePass(), s.roleId()));
                var error = discord.send(s.guildId(), s.channelId(), message);
                if (error.isPresent()) {
                    store.failed(post.id(), s.channelId());
                    BunnyLog.warning("[Console] Post to " + s.guildId() + " failed: " + error.get());
                } else sent++;
            }
            if (sent > 0) BunnyLog.success("[Console] Delivered " + post.id() + " to " + sent + " channel(s)");
        }
    }
}
