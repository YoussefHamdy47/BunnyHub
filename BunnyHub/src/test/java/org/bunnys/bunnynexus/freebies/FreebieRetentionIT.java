package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.*;
import static org.junit.jupiter.api.Assertions.*;

/** Retention purge against a real isolated replica set: every "never delete" case, re-discovery, idempotency, bounds. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreebieRetentionIT {
    LocalReplicaSet replica;
    MongoDatabase db;
    FreebieRepository repository;
    MongoCollection<Document> offers, deliveries, notices;
    /** Records are written at t0 (the fixture candidates' timeline); the purge runs well past the 90-day window. */
    final Instant t0 = Instant.parse("2026-09-25T12:00:00Z"), now = Instant.parse("2027-06-01T00:00:00Z");
    final FreebieRetention.Limits limits = new FreebieRetention.Limits(500, 100, 100, 100);
    final String guild = "100000000000000001";

    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() {
        db = replica.client.getDatabase("retention_" + UUID.randomUUID().toString().replace("-", ""));
        repository = new FreebieRepository(db); repository.installIndexes();
        offers = db.getCollection(FreebieRepository.OFFERS);
        deliveries = db.getCollection(FreebieRepository.DELIVERIES);
        notices = db.getCollection(FreebieRepository.NOTICES);
    }

    /** A finished, posted-summary offer that left the feed at t0. */
    String offer(String id, FreebieOffer.State state) {
        repository.recordSeen(FreebieRepositoryIT.candidate(id, "PC, Steam"), t0);
        String offerId = FreebieOffer.idFor(id);
        if (state != FreebieOffer.State.PENDING)
            offers.updateOne(eq("_id", offerId), combine(set("state", state.name()), set("updatedAt", Date.from(t0)),
                    set("gone", true), set("summaryPosted", true)));
        return offerId;
    }
    String delivery(String offerId, String channel, String state, Bson... extra) {
        String id = offerId + "|" + channel;
        deliveries.insertOne(new Document("_id", id).append("schemaVersion", 1).append("offerId", offerId).append("guildId", guild)
                .append("channelId", channel).append("state", state).append("attempts", 1).append("createdAt", Date.from(t0))
                .append("nextAttemptAt", Date.from(t0)).append("finishedAt", Date.from(t0)));
        if (extra.length > 0) deliveries.updateOne(eq("_id", id), combine(extra));
        return id;
    }
    FreebieRetention.Result purge() { return repository.retention().purge(now, 90, limits); }
    boolean exists(MongoCollection<Document> c, String id) { return c.find(eq("_id", id)).first() != null; }
    String state(String offerId) { return offers.find(eq("_id", offerId)).first().getString("state"); }

    @Test void purgesSettledRecordsLeavesATombstoneAndIsIdempotent() {
        String completed = offer("1", FreebieOffer.State.COMPLETED), rejected = offer("2", FreebieOffer.State.REJECTED);
        delivery(completed, "201", "SENT"); delivery(completed, "202", "FAILED"); delivery(completed, "203", "CANCELLED");
        assertEquals(new FreebieRetention.Result(3, 2, 0, false), purge());
        assertEquals(0, deliveries.countDocuments());
        var tombstone = offers.find(eq("_id", completed)).first();
        assertEquals("PURGED", tombstone.getString("state"));
        assertNotNull(tombstone.getDate("purgedAt"));
        assertNull(tombstone.getString("title"), "offer content is gone; only ID and purge time remain");
        assertEquals("PURGED", state(rejected));
        assertTrue(repository.offer(completed).isEmpty());
        assertEquals(new FreebieRetention.Result(0, 0, 0, false), purge(), "second run finds nothing");
        assertEquals(2, offers.countDocuments());
    }

    @Test void purgedOfferThatReappearsInTheFeedIsNotReviewedAgain() {
        String id = offer("7", FreebieOffer.State.EXPIRED);
        assertEquals(1, purge().offers());
        assertFalse(repository.recordSeen(FreebieRepositoryIT.candidate("7", "PC, Steam"), now), "not recorded as new");
        assertEquals("PURGED", state(id));
        assertTrue(repository.reviews().claim(now).isEmpty(), "nothing queued for review");
        assertTrue(repository.offersIn(FreebieOffer.State.PENDING, 10).isEmpty());
        assertTrue(repository.markMissing(Set.of()).isEmpty());
        assertTrue(repository.liveApproved(Optional.empty(), now, 10).isEmpty());
        assertTrue(repository.summaries().awaiting(now, 10).isEmpty());
        assertEquals(0, repository.health().snapshot(now).reviews().total());
        assertEquals(0, purge().offers(), "tombstones are not purged again");
    }

    @Test void neverDeletesOpenBlockedLeasedOrNoticePendingDeliveries() {
        String offer = offer("1", FreebieOffer.State.STOPPED);
        var kept = List.of(delivery(offer, "201", "PENDING"), delivery(offer, "202", "BLOCKED"),
                delivery(offer, "203", "SENDING", set("leaseToken", "t"), set("leaseUntil", Date.from(now.plusSeconds(60)))),
                delivery(offer, "204", "FAILED", set("noticePending", true)),
                delivery(offer, "205", "SENT", set("leaseToken", "stale")),
                delivery(offer, "206", "FAILED", set("retryPreviewUntil", Date.from(now.plusSeconds(60)))),
                delivery(offer, "207", "SENT", set("finishedAt", Date.from(now.minus(Duration.ofDays(89))))));
        String old = delivery(offer, "208", "SENT");
        assertEquals(new FreebieRetention.Result(1, 0, 0, false), purge());
        for (String id : kept) assertTrue(exists(deliveries, id), id);
        assertFalse(exists(deliveries, old));
        assertEquals("STOPPED", state(offer), "an offer with deliveries left is kept");
    }

    @Test void keepsDeliveriesWhileTheirOfferStillNeedsThem() {
        String unsent = offer("1", FreebieOffer.State.STOPPED);
        offers.updateOne(eq("_id", unsent), set("summaryPosted", false));
        String leased = offer("2", FreebieOffer.State.COMPLETED);
        offers.updateOne(eq("_id", leased), set("summaryLeaseUntil", Date.from(now.plusSeconds(60))));
        String live = offer("3", FreebieOffer.State.COMPLETED); // Catch-up relies on the row to send once per channel.
        offers.updateOne(eq("_id", live), combine(set("gone", false), set("endsAt", null)));
        String approved = offer("4", FreebieOffer.State.APPROVED);
        String pending = offer("5", FreebieOffer.State.PENDING);
        for (String o : List.of(unsent, leased, live, approved, pending)) delivery(o, "201", "SENT");
        String settled = offer("6", FreebieOffer.State.COMPLETED);
        delivery(settled, "201", "SENT");
        var result = purge();
        assertEquals(1, result.deliveries());
        for (String o : List.of(unsent, leased, live, approved, pending)) {
            assertTrue(exists(deliveries, o + "|201"), o);
            assertNotEquals("PURGED", state(o), o);
        }
        assertEquals("PURGED", state(settled));
    }

    @Test void keepsOffersWithPendingWorkOrStillInTheWindow() {
        String unsent = offer("1", FreebieOffer.State.COMPLETED);
        offers.updateOne(eq("_id", unsent), set("summaryPosted", false));
        String summaryLease = offer("2", FreebieOffer.State.STOPPED);
        offers.updateOne(eq("_id", summaryLease), set("summaryLeaseUntil", Date.from(now.plusSeconds(60))));
        String reviewLease = offer("3", FreebieOffer.State.EXPIRED);
        offers.updateOne(eq("_id", reviewLease), set("reviewPostUntil", Date.from(now.plusSeconds(60))));
        String recentlyListed = offer("4", FreebieOffer.State.REJECTED);
        offers.updateOne(eq("_id", recentlyListed), set("lastSeenAt", Date.from(now.minus(Duration.ofDays(10)))));
        String notEnded = offer("5", FreebieOffer.State.REJECTED);
        offers.updateOne(eq("_id", notEnded), set("endsAt", Date.from(now.plus(Duration.ofDays(1)))));
        String recentlyDecided = offer("6", FreebieOffer.State.REJECTED);
        offers.updateOne(eq("_id", recentlyDecided), set("updatedAt", Date.from(now.minus(Duration.ofDays(5)))));
        String noticeWork = offer("7", FreebieOffer.State.STOPPED);
        notices.insertOne(new Document("_id", guild).append("noticeState", "PENDING").append("noticeOfferId", noticeWork));
        String pending = offer("8", FreebieOffer.State.PENDING);
        String approved = offer("9", FreebieOffer.State.APPROVED);
        assertEquals(0, purge().offers());
        for (String o : List.of(unsent, summaryLease, reviewLease, recentlyListed, notEnded, recentlyDecided, noticeWork, pending, approved))
            assertNotEquals("PURGED", state(o), o);
    }

    @Test void keepsNoticeStateThatStillApplies() {
        Date old = Date.from(t0);
        notices.insertOne(new Document("_id", "1").append("noticeState", "SENT").append("noticeReconciled", true).append("lastNoticeAt", old));
        notices.insertOne(new Document("_id", "2").append("lastNoticeAt", old)); // Legacy daily-limit record.
        notices.insertOne(new Document("_id", "3").append("noticeState", "PENDING").append("noticeReconciled", false).append("lastNoticeAt", old));
        notices.insertOne(new Document("_id", "4").append("noticeState", "SENT").append("noticeReconciled", false).append("lastNoticeAt", old));
        notices.insertOne(new Document("_id", "5").append("noticeState", "SENT").append("noticeReconciled", true)
                .append("lastNoticeAt", Date.from(now.minus(Duration.ofHours(2)))));
        notices.insertOne(new Document("_id", "6").append("noticeState", "SENT").append("noticeReconciled", true)
                .append("lastNoticeAt", old).append("noticeLeaseUntil", Date.from(now.plusSeconds(60))));
        notices.insertOne(new Document("_id", guild).append("noticeState", "SENT").append("noticeReconciled", true).append("lastNoticeAt", old));
        String offer = offer("1", FreebieOffer.State.STOPPED);
        delivery(offer, "201", "FAILED", set("noticePending", true)); // Still judged against this server's lastNoticeAt.
        assertEquals(2, purge().notices());
        assertFalse(exists(notices, "1")); assertFalse(exists(notices, "2"));
        for (String id : List.of("3", "4", "5", "6", guild)) assertTrue(exists(notices, id), id);
    }

    @Test void eachRunIsBoundedAndTheRestFollowsSoon() {
        var small = new FreebieRetention.Limits(3, 2, 2, 2);
        for (int i = 1; i <= 3; i++) offer(Integer.toString(i), FreebieOffer.State.REJECTED);
        String sending = offer("9", FreebieOffer.State.COMPLETED);
        for (int i = 0; i < 7; i++) delivery(sending, "20" + i, "SENT");
        var first = repository.retention().purge(now, 90, small);
        assertEquals(new FreebieRetention.Result(3, 2, 0, true), first);
        assertFalse(repository.retention().due(now.plusSeconds(59)));
        assertTrue(repository.retention().due(now.plus(FreebieRetention.BACKLOG_EVERY)));
        var last = repository.retention().last().orElseThrow();
        assertEquals(new FreebieRetention.Last(now, 3, 2, 0), last);
        assertEquals(new FreebieRetention.Result(3, 1, 0, true), repository.retention().purge(now, 90, small));
        assertEquals(new FreebieRetention.Result(1, 1, 0, false), repository.retention().purge(now, 90, small));
        assertFalse(repository.retention().due(now.plus(Duration.ofMinutes(59))));
        assertTrue(repository.retention().due(now.plus(FreebieRetention.EVERY)));
        assertEquals(0, deliveries.countDocuments());
    }

    @Test void heldOffersDoNotStallTheBatch() {
        var small = new FreebieRetention.Limits(10, 10, 10, 2);
        String unsent = offer("1", FreebieOffer.State.STOPPED);
        offers.updateOne(eq("_id", unsent), set("summaryPosted", false));
        for (int i = 0; i < 4; i++) delivery(unsent, "20" + i, "SENT", set("finishedAt", Date.from(t0.minusSeconds(100 - i))));
        String settled = offer("2", FreebieOffer.State.STOPPED);
        delivery(settled, "209", "SENT");
        assertEquals(1, repository.retention().purge(now, 90, small).deliveries());
        assertEquals(4, deliveries.countDocuments());
    }
}
