package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bunnys.bunnynexus.ratings.GameRating;
import org.bunnys.bunnynexus.ratings.SteamRatings;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;

import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.*;

/** Real MongoDB: each game is looked up once, a failure is retried later, and the saved rating reaches the facts. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreebieRatingsIT {
    LocalReplicaSet replica;
    MongoCollection<Document> offers;
    Instant now = Instant.parse("2026-11-26T16:01:00Z");
    final List<String> asked = new ArrayList<>();
    SteamRatings.Lookup answer = new SteamRatings.Found(new GameRating(1607680, 78, 4265, "Mostly Positive"));

    final FreebieRatings.Source source = new FreebieRatings.Source() {
        @Override public SteamRatings.Lookup forApp(long appId) { asked.add("app " + appId); return answer; }
        @Override public SteamRatings.Lookup forTitle(String title, Optional<String> publisher) {
            asked.add(title + " / " + publisher.orElse("-"));
            return answer;
        }
    };

    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() {
        offers = replica.client.getDatabase("ratings_it_" + UUID.randomUUID().toString().replace("-", "")).getCollection(FreebieRepository.OFFERS);
        asked.clear();
        answer = new SteamRatings.Found(new GameRating(1607680, 78, 4265, "Mostly Positive"));
    }

    FreebieRatings ratings() { return new FreebieRatings(offers, source, Clock.fixed(now, ZoneOffset.UTC)); }

    void offer(String id, String store, String title, String claim, String seller) {
        var doc = new Document("_id", id).append("state", "PENDING").append("store", store).append("title", title)
                .append("claimUrl", claim).append("pageUrl", claim).append("discoveredAt", Date.from(now));
        if (seller != null) doc.append("seller", seller);
        offers.insertOne(doc);
    }

    @Test void eachPcGameIsLookedUpOnceByAppOrByTitleAndPublisher() {
        offer("epic:bread", "epic", "Bread and Fred", "https://store.epicgames.com/en-US/p/bread-and-fred-1db362", "Atari");
        offer("gamerpower:1", "steam", "Some Game (Steam) Giveaway", "https://store.steampowered.com/app/123/Some_Game/", null);
        offer("gamerpower:2", "epic", "Bread & Fred (Epic Games) Giveaway", "https://www.gamerpower.com/open/x", null);
        offer("gamerpower:3", "playstation", "A Console Game", "https://www.gamerpower.com/open/y", null);
        assertEquals(3, ratings().run());
        assertEquals(Set.of("Bread and Fred / Atari", "app 123", "Bread & Fred / -"), new HashSet<>(asked));
        var saved = FreebieFacts.read(offers.find(eq("_id", "epic:bread")).first());
        assertEquals(Optional.of(new GameRating(1607680, 78, 4265, "Mostly Positive")), saved.rating());
        assertNull(offers.find(eq("_id", "gamerpower:3")).first().getDate("ratingCheckedAt"), "console games are never looked up");

        asked.clear();
        assertEquals(0, ratings().run(), "never twice");
        assertTrue(asked.isEmpty());
    }

    @Test void noMatchIsRememberedAndAFailureIsRetriedLater() {
        offer("epic:x", "epic", "Unknown Game", "https://store.epicgames.com/en-US/p/unknown", "Nobody");
        answer = new SteamRatings.NoMatch("no Steam game with that exact title");
        var worker = ratings();
        worker.run();
        var doc = offers.find(eq("_id", "epic:x")).first();
        assertEquals(Optional.empty(), FreebieFacts.read(doc).rating());
        assertEquals("no Steam game with that exact title", doc.getString("ratingMiss"));

        offer("epic:y", "epic", "Another Game", "https://store.epicgames.com/en-US/p/another", null);
        answer = new SteamRatings.Failed("network: SocketTimeoutException");
        assertEquals(1, worker.run());
        assertNull(offers.find(eq("_id", "epic:y")).first().getDate("ratingCheckedAt"));
        assertEquals(0, worker.run(), "not again right away");
    }
}
