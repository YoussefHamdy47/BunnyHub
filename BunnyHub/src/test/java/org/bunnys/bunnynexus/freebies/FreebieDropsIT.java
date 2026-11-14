package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.mongodb.client.model.Filters.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real MongoDB: drop windows shared by concurrent planners, one page per drop under concurrent claims, roles only on
 * page 1, the drop page's capacity, ended edits routed through the page for drop rows and card by card for old rows,
 * and the per-game rollback.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreebieDropsIT {
    static final String GUILD = "100000000000000000", CHANNEL = "200000000000000000", OTHER = "200000000000000001",
            ROLE = "300000000000000000";
    static final Duration WINDOW = Duration.ofSeconds(120), LEASE = Duration.ofMinutes(3);
    final Instant now = Instant.parse("2026-10-23T12:00:00Z");
    LocalReplicaSet replica;
    MongoDatabase db;
    FreebieRepository repository;
    MongoCollection<Document> deliveries;

    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() {
        db = replica.client.getDatabase("drops_it_" + UUID.randomUUID().toString().replace("-", ""));
        repository = new FreebieRepository(db);
        repository.installIndexes();
        repository.dropWindow(Optional.of(WINDOW));
        deliveries = db.getCollection(FreebieRepository.DELIVERIES);
    }

    static FreebieOffer game(int i) { return FreebieAnnouncementCardsTest.epic("g" + i, "Game " + i); }
    static FreebieSubscriptions.Subscription in(String channel) {
        return new FreebieSubscriptions.Subscription(GUILD, channel, FreebieStore.EPIC, Optional.of(ROLE));
    }
    Document row(FreebieOffer offer, String channel) { return deliveries.find(eq("_id", offer.id() + "|" + channel)).first(); }

    @Test void approvalsPlannedAtOnceInOneChannelShareOneDropAndWaitForItsEnd() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Integer>> approvals = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                var offer = game(i);
                approvals.add(() -> repository.planDeliveries(offer, List.of(in(CHANNEL), in(OTHER)), now.plusSeconds(offer.id().length() % 3)));
            }
            for (var f : pool.invokeAll(approvals)) assertEquals(2, f.get());
        }
        Set<String> here = new HashSet<>(), there = new HashSet<>();
        for (int i = 0; i < 8; i++) { here.add(row(game(i), CHANNEL).getString("dropKey")); there.add(row(game(i), OTHER).getString("dropKey")); }
        assertEquals(1, here.size(), "one drop per channel, whatever the order of the concurrent planners");
        assertEquals(1, there.size());
        assertNotEquals(here, there, "each channel has its own drop");
        var first = row(game(0), CHANNEL);
        assertTrue(FreebieDrops.isDrop(first.getString("dropKey")));
        // The window starts at whichever approval opened it first (the planners' clocks differ by up to 2 s).
        Instant start = Instant.ofEpochMilli(Long.parseLong(first.getString("dropKey").substring(first.getString("dropKey").lastIndexOf('|') + 1)));
        assertTrue(!start.isBefore(now) && !start.isAfter(now.plusSeconds(2)));
        Instant end = start.plus(WINDOW);
        for (int i = 0; i < 8; i++) {
            assertEquals(Date.from(end), row(game(i), CHANNEL).getDate("nextAttemptAt"), "every row waits for the window's end");
            assertTrue(!row(game(i), OTHER).getDate("nextAttemptAt").toInstant().isAfter(now.plusSeconds(2).plus(WINDOW)));
        }
        assertTrue(repository.claim(now.plus(WINDOW).minusSeconds(1), LEASE).isEmpty(), "nothing is posted before the window ends");
        var claimed = repository.claim(now.plusSeconds(2).plus(WINDOW), LEASE).orElseThrow();
        assertEquals(Optional.of(deliveries.find(eq("_id", claimed.id())).first().getString("dropKey")), claimed.announcementKey(),
                "the drop is the page key");

        // After the window, the next approval opens a new drop.
        repository.planDeliveries(game(9), List.of(in(CHANNEL)), end.plusSeconds(1));
        assertNotEquals(first.getString("dropKey"), row(game(9), CHANNEL).getString("dropKey"));
    }

    @Test void onlyOneClaimCreatesTheDropsFirstPage() throws Exception {
        String key = FreebieDrops.key(CHANNEL, now);
        var store = repository.announcements();
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> claims = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                int n = i;
                claims.add(() -> store.createWithRoles(key, GUILD, CHANNEL, 1, List.of(ROLE), List.of("o" + n), List.of("o" + n + "|c"),
                        "t" + n, LEASE, now).isPresent());
            }
            int created = 0;
            for (var f : pool.invokeAll(claims)) if (f.get()) created++;
            assertEquals(1, created, "the page _id admits one first post per drop");
        }
        var page = store.pages(key, CHANNEL).getFirst();
        assertEquals(List.of(ROLE), FreebieAnnouncementStore.roles(page));
        assertEquals(FreebieAnnouncementStore.DROP_PAGE_SIZE, page.getInteger("capacity"));
        String token = page.getString("token");
        assertTrue(store.submitting(page.getString("_id"), token, now));
        assertTrue(store.sent(page.getString("_id"), token, "m1", false, now));
        for (int i = 0; i < 12; i++) store.join(page.getString("_id"), "late" + i, "late" + i + "|c", now);
        assertEquals(FreebieAnnouncementStore.DROP_PAGE_SIZE, store.get(page.getString("_id")).orElseThrow().getList("members", String.class).size(),
                "a drop page takes late games silently up to ten");
    }

    @Test void overflowPagesNeverStoreARole() {
        String key = FreebieDrops.key(CHANNEL, now);
        var store = repository.announcements();
        var first = store.createWithRoles(key, GUILD, CHANNEL, 1, List.of(ROLE), List.of("a"), List.of("a|c"), "t", LEASE, now).orElseThrow();
        var second = store.createWithRoles(key, GUILD, CHANNEL, 2, List.of(ROLE), List.of("o"), List.of("o|c"), "t", LEASE, now).orElseThrow();
        assertEquals(List.of(), FreebieAnnouncementStore.roles(second), "page 1 may post, so page 2 never pings");
        assertNull(second.getString("roleId"));
        assertFalse(second.getBoolean("lead"));

        // Finding 5: when every earlier page was withdrawn without a post, the next page is the drop's lead and pings.
        assertTrue(store.cancel(first.getString("_id"), "t", now));
        var third = store.createWithRoles(key, GUILD, CHANNEL, 3, List.of(ROLE), List.of("p"), List.of("p|c"), "t", LEASE, now).orElseThrow();
        assertEquals(List.of(), FreebieAnnouncementStore.roles(third), "page 2 is still in flight");
        db.getCollection(FreebieAnnouncementStore.COLLECTION).updateOne(eq("_id", second.getString("_id")),
                com.mongodb.client.model.Updates.set("state", "FAILED"));
        var fourth = store.createWithRoles(key, GUILD, CHANNEL, 4, List.of(ROLE), List.of("q"), List.of("q|c"), "t", LEASE, now).orElseThrow();
        assertEquals(List.of(), FreebieAnnouncementStore.roles(fourth), "page 3 is still in flight");
        assertFalse(fourth.getBoolean("lead"));
        db.getCollection(FreebieAnnouncementStore.COLLECTION).updateMany(com.mongodb.client.model.Filters.in("_id", third.getString("_id"), fourth.getString("_id")),
                com.mongodb.client.model.Updates.set("state", "FAILED"));
        var fifth = store.createWithRoles(key, GUILD, CHANNEL, 5, List.of(ROLE), List.of("r"), List.of("r|c"), "t", LEASE, now).orElseThrow();
        assertEquals(List.of(ROLE), FreebieAnnouncementStore.roles(fifth), "no earlier page posted or in flight: this page leads");
        assertTrue(fifth.getBoolean("lead"));
    }

    @Test void endedDropRowsAreEditedThroughTheirPageAndOldCardsOneByOne() {
        var offer = game(1);
        repository.dropWindow(Optional.empty());
        repository.planDeliveries(offer, List.of(in(OTHER)), now); // an older per-game card
        repository.dropWindow(Optional.of(WINDOW));
        repository.planDeliveries(offer, List.of(in(CHANNEL)), now);
        deliveries.updateMany(eq("offerId", offer.id()), com.mongodb.client.model.Updates.combine(
                com.mongodb.client.model.Updates.set("state", "SENT"), com.mongodb.client.model.Updates.set("messageId", "m")));
        var planned = repository.lifecycle().planEdits(offer.id(), false, now);
        assertEquals(1, planned.queued(), "only the old per-game card gets its own ended edit");
        var jobs = db.getCollection(FreebieLifecycleStore.COLLECTION);
        assertEquals(1, jobs.countDocuments(eq("_id", FreebieLifecycleStore.editId(offer.id() + "|" + OTHER))));
        assertEquals(0, jobs.countDocuments(eq("_id", FreebieLifecycleStore.editId(offer.id() + "|" + CHANNEL))));
    }

    @Test void theRollbackPlansPerGameRowsAndOlderPagesNeverTakeDropRows() {
        var offer = game(2);
        db.getCollection(FreebieRepository.OFFERS).insertOne(new Document("_id", offer.id()).append("route", "announcement")
                .append("announcementKey", "epic|m|1"));
        repository.planDeliveries(offer, List.of(in(CHANNEL)), now);
        assertEquals("epic|m|1", row(offer, CHANNEL).getString("announcementKey"));
        assertNotNull(row(offer, CHANNEL).getString("dropKey"));
        assertTrue(repository.routes().announcementPeers("epic|m|1", CHANNEL, "other", 10).isEmpty(),
                "a row in a drop is never frozen into an older release page too");

        repository.dropWindow(Optional.empty());
        var legacy = game(3);
        repository.planDeliveries(legacy, List.of(in(CHANNEL)), now);
        assertNull(row(legacy, CHANNEL).getString("dropKey"), "FREEBIE_GROUPED_ANNOUNCEMENTS=false posts per game");
        assertEquals(Date.from(now), row(legacy, CHANNEL).getDate("nextAttemptAt"));
        var claimed = repository.claim(now, LEASE).orElseThrow();
        assertEquals(legacy.id(), claimed.offerId());
        assertTrue(claimed.announcementKey().isEmpty(), "no page key: the per-game path");
    }

    @Test void aCatchUpRequestIsOneDropOfItsOwn() {
        String key = FreebieDrops.catchUpKey(CHANNEL, now);
        for (int i = 4; i < 7; i++) assertTrue(repository.planDelivery(game(i), in(CHANNEL), Optional.of(key), now));
        for (int i = 4; i < 7; i++) {
            var row = row(game(i), CHANNEL);
            assertEquals(key, row.getString("dropKey"));
            assertTrue(row.getBoolean("catchUp"));
            assertEquals(Date.from(now.plus(FreebieDrops.CATCH_UP_HOLD)), row.getDate("nextAttemptAt"), "held until the request finishes");
        }
        assertTrue(repository.claimCatchUp(now.plusSeconds(60), Duration.ofMinutes(3)).isEmpty(), "a slow request is never claimed half-inserted");
        assertEquals(3, repository.releaseDrop(key, now.plusSeconds(60)));
        assertEquals(Date.from(now.plusSeconds(60)), row(game(5), CHANNEL).getDate("nextAttemptAt"));
        assertEquals(0, repository.releaseDrop(key, now.plusSeconds(61)), "releasing twice changes nothing");
        assertEquals(2, repository.routes().announcementPeers(key, CHANNEL, game(4).id(), 20).size());
        assertFalse(repository.planDelivery(game(4), in(CHANNEL), Optional.of(key), now), "still at most once per channel");
    }
}
