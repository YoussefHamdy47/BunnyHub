package org.bunnys.bunnynexus.updates;

import com.mongodb.client.MongoDatabase;
import org.bunnys.bunnynexus.freebies.LocalReplicaSet;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real MongoDB: ads saved, replaced, paused, removed, and the active list. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdStoreIT {
    LocalReplicaSet replica;
    MongoDatabase db;

    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() { db = replica.client.getDatabase("ads_it_" + UUID.randomUUID().toString().replace("-", "")); }

    @Test void adsRoundTripAndOnlyActiveOnesShow() {
        var store = new AdStore(db);
        var full = new Ad("deals", Optional.of("Deals board"), "Big discounts.\nCheapest first.", Optional.of(URI.create("https://cdn.example/a.png")),
                Optional.of(new Ad.Link("Learn more", URI.create("https://example.com"))), Ad.Audience.NO_DEALS_BOARD, true,
                Instant.parse("2026-11-25T10:00:00Z"));
        store.save(full);
        store.save(new Ad("support", Optional.empty(), "Join our server.", Optional.empty(), Optional.empty(), Ad.Audience.ALL, true,
                Instant.parse("2026-11-25T11:00:00Z")));
        assertEquals(Optional.of(full), store.get("deals"));
        assertEquals(List.of("support", "deals"), store.all().stream().map(Ad::id).toList(), "newest first");

        var edited = new Ad("deals", Optional.empty(), "New text.", Optional.empty(), Optional.empty(), Ad.Audience.ALL, true,
                Instant.parse("2026-11-26T00:00:00Z"));
        store.save(edited);
        assertEquals(full.createdAt(), store.get("deals").orElseThrow().createdAt(), "replacing keeps its place");
        assertEquals("New text.", store.get("deals").orElseThrow().text());

        assertTrue(store.setOn("support", false));
        assertEquals(List.of("deals"), store.active().stream().map(Ad::id).toList(), "paused ads are not active");
        assertTrue(store.remove("deals"));
        assertTrue(store.active().isEmpty());
        assertFalse(store.remove("deals"));
        assertFalse(store.setOn("nope", true));
    }

    @Test void theBotsAdsAreAddedByVersionAndStayDeleted() {
        var store = new AdStore(db);
        var starters = StarterAds.all("795361755223556116", Instant.parse("2026-11-28T12:00:00Z"));
        assertEquals(7, store.seed(starters));
        assertEquals(7, store.active().size());
        var deal = store.get("deal-spotlight").orElseThrow();
        assertEquals(Optional.of("{deal.emoji}"), deal.emoji(), "placeholders are stored as written");
        assertEquals(30, deal.priority());
        assertEquals("{deal.url}", deal.links().getFirst().url());
        assertEquals(Ad.When.BIG_GAME, store.get("big-game").orElseThrow().when());
        store.remove("join-community");
        store.setOn("deals-board", false);
        assertEquals(0, store.seed(starters), "nothing new");
        assertTrue(store.get("join-community").isEmpty(), "a deleted ad stays deleted");
        assertFalse(store.get("deals-board").orElseThrow().on(), "a paused ad stays paused");
    }

    @Test void adsFromBeforeVersionsAreUpdatedOnceAndKeepTheirPause() {
        var store = new AdStore(db);
        var old = Instant.parse("2026-11-25T12:00:00Z");
        store.seedOnce(List.of(new Ad("get-bunnynexus", Optional.of("Get BunnyNexus"), "Old text.", Optional.empty(),
                Optional.of(new Ad.Link("Add BunnyNexus", URI.create("https://example.com"))), Ad.Audience.ALL, true, old)));
        store.setOn("get-bunnynexus", false);
        assertEquals(7, store.seed(StarterAds.all("795361755223556116", Instant.parse("2026-11-28T12:00:00Z"))),
                "version 2 replaces the first two; the five new ones are added");
        var updated = store.get("get-bunnynexus").orElseThrow();
        assertEquals(Optional.of(org.bunnys.utils.AppDesign.Emojis.VERIFY), updated.emoji());
        assertFalse(updated.on(), "still paused");
        assertEquals(old, updated.createdAt(), "keeps its place in the rotation");
    }
}
