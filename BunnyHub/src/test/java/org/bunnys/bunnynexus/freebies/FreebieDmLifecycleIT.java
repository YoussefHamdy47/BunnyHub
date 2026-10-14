package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreebieDmLifecycleIT {
    private LocalReplicaSet replica;
    private FreebieRepository repository;
    private FreebieLifecyclePlanner planner;
    private MongoCollection<Document> offers, dms, edits, deliveries;
    private final Instant now = Instant.parse("2026-10-13T00:00:00Z");
    private final String recipient = "400000000000000001", channel = "500000000000000001", message = "600000000000000001";
    private final String offerId = "gamerpower:1", dmId = offerId + "|" + recipient;

    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() {
        var db = replica.client.getDatabase("dm_lifecycle_" + UUID.randomUUID().toString().replace("-", ""));
        repository = new FreebieRepository(db);
        repository.installIndexes();
        planner = new FreebieLifecyclePlanner(repository, new FreebieSubscriptions(db));
        offers = db.getCollection(FreebieRepository.OFFERS);
        dms = db.getCollection(FreebieDmJobs.COLLECTION);
        edits = db.getCollection(FreebieLifecycleStore.COLLECTION);
        deliveries = db.getCollection(FreebieRepository.DELIVERIES);
        repository.recordSeen(FreebieRepositoryIT.candidate("1", "PC, Steam"), now);
        offers.updateOne(eq("_id", offerId), combine(set("state", "COMPLETED"), set("decidedBy", "owner"),
                set("summaryPosted", true), set("updatedAt", Date.from(now)), set("endsAt", Date.from(now.minusSeconds(60)))));
    }

    private void sent(Instant at) {
        dms.insertOne(new Document("_id", dmId).append("offerId", offerId).append("userId", recipient)
                .append("channelId", channel).append("messageId", message).append("state", "SENT").append("finishedAt", Date.from(at)));
    }

    @Test void concurrentPlanningKeepsChannelAndDmKeysSeparateAndPersistsPrivateRoute() throws Exception {
        sent(now);
        deliveries.insertOne(new Document("_id", dmId).append("offerId", offerId).append("guildId", "100000000000000001")
                .append("channelId", recipient).append("messageId", "700000000000000001").append("state", "SENT"));
        var results = FreebieLifecycleIT.race(6, () -> planner.ended(offerId, FreebieLifecycle.End.WITHDRAWN, now));
        assertEquals(2, results.stream().mapToInt(FreebieLifecycleStore.Planned::queued).sum());
        assertEquals(2, edits.countDocuments());
        var stored = edits.find(eq("_id", "edit|dm|" + dmId)).first();
        assertNotNull(stored);
        assertEquals(recipient, stored.getString("recipientId"));
        assertEquals(channel, stored.getString("channelId"));
        assertEquals(message, stored.getString("messageId"));
        assertTrue(stored.getBoolean("withdrawn"));
        assertEquals("dm|" + dmId, stored.getString("deliveryId"));
        var a = repository.lifecycle().claim(now, Duration.ofMinutes(3)).orElseThrow();
        var b = repository.lifecycle().claim(now, Duration.ofMinutes(3)).orElseThrow();
        var personal = a.recipientId() == null ? b : a;
        assertEquals(recipient, personal.recipientId());
        assertEquals(FreebieDm.reference(dmId), FreebieMessages.reference(personal.deliveryId()));
        assertTrue(offers.find(eq("_id", offerId)).first().getBoolean("dmEndMarkDone"));
        assertEquals(0, planner.run(now).edits());
    }

    @Test void oldChannelCompletionDoesNotHidePersonalCardsFromBackfill() {
        sent(now.minusSeconds(100));
        offers.updateOne(eq("_id", offerId), set("endMarkDone", true));
        assertEquals(1, planner.run(now).edits());
        assertEquals(0, planner.run(now).edits());
        assertTrue(offers.find(eq("_id", offerId)).first().getBoolean("dmEndMarkDone"));
    }

    @Test void aDmAcceptedAfterFirstPlanningIsPickedUpLater() {
        repository.dmJobs().plan(offerId, List.of(recipient), now);
        var leased = repository.dmJobs().claim(now, Duration.ofMinutes(3)).orElseThrow();
        assertEquals(0, planner.run(now).edits());
        assertFalse(offers.find(eq("_id", offerId)).first().getBoolean("dmEndMarkDone", false));
        assertTrue(repository.dmJobs().submitting(leased, channel, now));
        assertTrue(repository.dmJobs().sent(leased, message, now));
        assertEquals(1, planner.run(now).edits());
        assertTrue(offers.find(eq("_id", offerId)).first().getBoolean("dmEndMarkDone"));
    }

    @Test void missingSavedDestinationsAreCountedWithoutRetryingForever() {
        sent(now);
        dms.updateOne(eq("_id", dmId), unset("channelId"));
        var run = planner.run(now);
        assertEquals(0, run.edits());
        assertEquals(1, run.withoutMessage());
        assertEquals(0, edits.countDocuments());
        assertTrue(offers.find(eq("_id", offerId)).first().getBoolean("dmEndMarkDone"));
    }

    @Test void aDmFinishingBetweenSentScanAndCompletionCheckCannotBeMissed() {
        repository.dmJobs().plan(offerId, List.of(recipient), now);
        var leased = repository.dmJobs().claim(now, Duration.ofMinutes(3)).orElseThrow();
        var store = spy(repository.lifecycle());
        var repo = spy(repository);
        doReturn(store).when(repo).lifecycle();
        doAnswer(invocation -> {
            var result = invocation.callRealMethod();
            repository.dmJobs().submitting(leased, channel, now);
            repository.dmJobs().sent(leased, message, now);
            return result;
        }).when(store).planEdits(offerId, false, now);
        var racing = new FreebieLifecyclePlanner(repo, mock(FreebieSubscriptions.class));
        racing.ended(offerId, FreebieLifecycle.End.ENDED, now);
        assertFalse(offers.find(eq("_id", offerId)).first().getBoolean("dmEndMarkDone", false));
        assertEquals(1, planner.run(now).edits());
    }

    @Test void retentionKeepsPrivateCardsAndOffersWithOpenEditsThenPurgesThem() {
        Instant old = now.minus(Duration.ofDays(200));
        sent(old);
        planner.ended(offerId, FreebieLifecycle.End.ENDED, old);
        offers.updateOne(eq("_id", offerId), combine(set("gone", true), set("lastSeenAt", Date.from(old)),
                set("updatedAt", Date.from(old)), set("endsAt", Date.from(old))));
        assertEquals(0, repository.retention().purge(now, 90, FreebieRetention.Limits.DEFAULT).offers());
        assertNotNull(dms.find(eq("_id", dmId)).first());
        assertNotNull(edits.find(eq("_id", "edit|dm|" + dmId)).first());
        var job = repository.lifecycle().claim(now, Duration.ofMinutes(3)).orElseThrow();
        assertTrue(repository.lifecycle().done(job, message, now));
        Instant later = now.plus(Duration.ofDays(100));
        repository.retention().purge(later, 90, FreebieRetention.Limits.DEFAULT);
        assertNull(dms.find(eq("_id", dmId)).first());
        assertNull(edits.find(eq("_id", job.id())).first());
        assertEquals("PURGED", offers.find(eq("_id", offerId)).first().getString("state"));
    }

    @Test void retentionKeepsOldSentDmsUntilLiveOrRecentlyEndedOffersArePlanned() {
        sent(now.minus(Duration.ofDays(200)));
        offers.updateOne(eq("_id", offerId), set("endsAt", Date.from(now.plus(Duration.ofDays(1)))));
        repository.retention().purge(now, 90, FreebieRetention.Limits.DEFAULT);
        assertNotNull(dms.find(eq("_id", dmId)).first());
        offers.updateOne(eq("_id", offerId), set("endsAt", Date.from(now.minusSeconds(1))));
        repository.retention().purge(now, 90, FreebieRetention.Limits.DEFAULT);
        assertNotNull(dms.find(eq("_id", dmId)).first());
        assertEquals(1, planner.run(now).edits());
    }

    @Test void oldEndedHistoryIsNotRewrittenAndEstimatedEndsDoNotTriggerEdits() {
        sent(now.minus(Duration.ofDays(200)));
        offers.updateOne(eq("_id", offerId), set("endsAtEstimated", true));
        assertEquals(0, planner.run(now).edits());
        offers.updateOne(eq("_id", offerId), combine(set("endsAtEstimated", false),
                set("lastSeenAt", Date.from(now.minus(Duration.ofDays(30)))), set("updatedAt", Date.from(now.minus(Duration.ofDays(30))))));
        assertEquals(0, planner.run(now).edits());
        repository.retention().purge(now, 90, FreebieRetention.Limits.DEFAULT);
        assertNull(dms.find(eq("_id", dmId)).first());
    }
}
