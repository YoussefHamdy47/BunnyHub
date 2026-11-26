package org.bunnys.bunnynexus.consoles;

import com.mongodb.client.MongoDatabase;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSubscriptions;
import org.bunnys.bunnynexus.freebies.LocalReplicaSet;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real MongoDB: state and posts read back as written, a channel is claimed once, and existing console channels are turned on once. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleNewsStoreIT {
    LocalReplicaSet replica;
    MongoDatabase db;
    final Instant now = Instant.parse("2026-11-26T18:00:00Z");

    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() { db = replica.client.getDatabase("console_it_" + UUID.randomUUID().toString().replace("-", "")); }

    @Test void stateAndPostsRoundTrip() {
        var store = new ConsoleNewsStore(db);
        store.installIndexes();
        assertEquals(ConsoleNews.State.EMPTY, store.state());
        Map<GamePass.Shelf, Map<String, Instant>> seen = new EnumMap<>(GamePass.Shelf.class);
        Map<GamePass.Shelf, List<String>> pending = new EnumMap<>(GamePass.Shelf.class);
        for (GamePass.Shelf s : GamePass.Shelf.values()) { seen.put(s, Map.of()); pending.put(s, List.of()); }
        seen.put(GamePass.Shelf.ADDED, Map.of("9NV8BWGH15GN", now));
        pending.put(GamePass.Shelf.COMING, List.of("9P1S707BPW0K"));
        var state = new ConsoleNews.State(List.of("psplus:monthly:x"), seen, pending, Optional.of(now), true, true, List.of("freeplay:/a"),
                Map.of("gamerpower:1", now), List.of("gamerpower:2"), Optional.of(now), true);
        store.save(state);
        assertEquals(state, store.state());

        var ps = new PsPlusPost("psplus:monthly:x", PsPlusPost.Kind.MONTHLY, "December", List.of("A"), false,
                URI.create("https://blog.playstation.com/a"), now, Optional.of(LocalDate.of(2026, 12, 1)), Optional.empty(), Optional.empty());
        store.create(ConsoleNews.Post.of(ps.id(), FreebieStore.PS_PLUS, now, ps));
        var game = new GamePass.Game("9NV8BWGH15GN", "Sniper", Optional.of("https://store-images.s-microsoft.com/x?w=800"), true, Optional.empty());
        store.create(ConsoleNews.Post.of("gamepass:1", FreebieStore.GAME_PASS, now.plusSeconds(1), Map.of(GamePass.Shelf.ADDED, List.of(game))));
        store.create(ConsoleNews.Post.of("gamepass:1", FreebieStore.GAME_PASS, now, Map.of()));
        var weekend = new FreeWeekend("freeplay:/w", "November 26-29", URI.create("https://www.purexbox.com/news/w"), now,
                Optional.of("https://images.purexbox.com/x/large.jpg"));
        store.create(ConsoleNews.Post.of(weekend.id(), FreebieStore.GAME_PASS, now.plusSeconds(2), weekend));
        var item = new ConsoleItem("gamerpower:7", "Emblem Key", URI.create("https://www.gamerpower.com/emblem"), List.of("Xbox", "PC"),
                Optional.of(now.plusSeconds(3600)), Optional.empty());
        store.create(ConsoleNews.Post.of("items:1", FreebieStore.CONSOLE_ITEMS, now.plusSeconds(3), List.of(item)));

        var open = store.open();
        assertEquals(4, open.size(), "the second create of the same ID changed nothing");
        assertEquals(Optional.of(ps), open.get(0).psPlus());
        assertEquals(List.of(game), open.get(1).gamePass().get(GamePass.Shelf.ADDED));
        assertEquals(Optional.of(weekend), open.get(2).weekend());
        assertEquals(List.of(item), open.get(3).items());

        assertTrue(store.claim("gamepass:1", "111"));
        assertFalse(store.claim("gamepass:1", "111"), "claimed once");
        store.failed("gamepass:1", "111");
        assertTrue(store.claim("gamepass:1", "111"), "a failed send can be tried again");
        var after = store.open().get(1);
        assertEquals(Set.of("111"), after.delivered());
        assertEquals(Map.of("111", 1), after.failures());
        store.close("gamepass:1");
        assertEquals(3, store.open().size());
    }

    @Test void existingConsoleChannelsAreTurnedOnOnce() {
        var store = new ConsoleNewsStore(db);
        var subs = new FreebieSubscriptions(db);
        subs.save(new FreebieSubscriptions.Subscription("g", "ps", FreebieStore.PLAYSTATION, Optional.of("r1")), "t", now);
        subs.save(new FreebieSubscriptions.Subscription("g", "xb", FreebieStore.XBOX, Optional.empty()), "t", now);
        subs.save(new FreebieSubscriptions.Subscription("g", "pc", FreebieStore.EPIC, Optional.empty()), "t", now);
        ConsoleNewsSystem.migrate(store, subs, now);
        assertEquals(Optional.of(Optional.of("r1")), subs.find("g", "ps", FreebieStore.PS_PLUS).map(FreebieSubscriptions.Subscription::roleId));
        assertTrue(subs.find("g", "xb", FreebieStore.GAME_PASS).isPresent());
        assertTrue(subs.find("g", "pc", FreebieStore.PS_PLUS).isEmpty());
        assertTrue(subs.find("g", "xb", FreebieStore.CONSOLE_ITEMS).isEmpty(), "in-game items are opt-in");
        assertTrue(store.state().migrated());

        subs.remove("g", "ps", Optional.of(FreebieStore.PS_PLUS));
        ConsoleNewsSystem.migrate(store, subs, now);
        assertTrue(subs.find("g", "ps", FreebieStore.PS_PLUS).isEmpty(), "a server that unticks it is never turned on again");
    }
}
