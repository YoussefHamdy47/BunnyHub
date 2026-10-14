package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bunnys.bunnynexus.freebies.FreebieLifecycle.Kind;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;
import static org.junit.jupiter.api.Assertions.*;

/** Lifecycle jobs against a real isolated replica set: idempotent concurrent planning, leases, the reminder key, retention. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreebieLifecycleIT {
    LocalReplicaSet replica;
    FreebieRepository repository;
    FreebieSubscriptions subscriptions;
    FreebieLifecycleStore jobs;
    MongoCollection<Document> offers, deliveries, lifecycle;
    /** The fixture candidate ends 2026-10-02T23:59 feed time, stored as 2026-10-03T01:59Z. */
    final Instant end = Instant.parse("2026-10-03T01:59:00Z"), now = end.minus(Duration.ofHours(24));
    final String guild = "100000000000000001";

    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() {
        MongoDatabase db = replica.client.getDatabase("lifecycle_" + UUID.randomUUID().toString().replace("-", ""));
        repository = new FreebieRepository(db); repository.installIndexes();
        subscriptions = new FreebieSubscriptions(db); subscriptions.installIndexes();
        jobs = repository.lifecycle();
        offers = db.getCollection(FreebieRepository.OFFERS);
        deliveries = db.getCollection(FreebieRepository.DELIVERIES);
        lifecycle = db.getCollection(FreebieLifecycleStore.COLLECTION);
    }

    String approved(String id) {
        repository.recordSeen(FreebieRepositoryIT.candidate(id, "PC, Steam"), now.minus(Duration.ofDays(5)));
        String offerId = FreebieOffer.idFor(id);
        offers.updateOne(eq("_id", offerId), combine(set("state", "APPROVED"), set("decidedBy", "333644367539470337"),
                set("fanoutDone", true), set("updatedAt", Date.from(now))));
        return offerId;
    }
    String sent(String offerId, String channel, String messageId, Instant at) {
        String id = offerId + "|" + channel;
        deliveries.insertOne(new Document("_id", id).append("schemaVersion", 1).append("offerId", offerId).append("guildId", guild)
                .append("channelId", channel).append("state", "SENT").append("messageId", messageId).append("attempts", 1)
                .append("createdAt", Date.from(at)).append("nextAttemptAt", Date.from(at)).append("finishedAt", Date.from(at)));
        return id;
    }
    void remindersOn(String channel, int hours) {
        assertEquals(FreebieSubscriptions.SaveResult.CREATED, subscriptions.setSection(guild, FreebieSection.MAJOR_PC, channel,
                EnumSet.of(FreebieStore.STEAM), Optional.empty(), hours, "1", now));
    }
    static <T> List<T> race(int threads, Callable<T> task) throws Exception {
        var pool = Executors.newFixedThreadPool(threads);
        var gate = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) futures.add(pool.submit(() -> { gate.await(); return task.call(); }));
            gate.countDown();
            List<T> results = new ArrayList<>();
            for (var f : futures) results.add(f.get(60, TimeUnit.SECONDS));
            return results;
        } finally { pool.shutdownNow(); }
    }

    @Test void concurrentEndPlanningCreatesOneEditPerSentAlert() throws Exception {
        String offer = approved("1");
        for (int i = 0; i < 30; i++) sent(offer, "2000000000000000" + (10 + i), "9000000000000000" + (10 + i), now);
        sent(offer, "200000000000000099", null, now); // historical row without a message ID
        deliveries.insertOne(new Document("_id", offer + "|201").append("offerId", offer).append("guildId", guild)
                .append("channelId", "201").append("state", "FAILED"));
        var planner = new FreebieLifecyclePlanner(repository, subscriptions);
        var results = race(8, () -> planner.ended(offer, FreebieLifecycle.End.WITHDRAWN, now));
        assertEquals(30, results.stream().mapToInt(FreebieLifecycleStore.Planned::queued).sum(), "every job inserted exactly once");
        assertTrue(results.stream().allMatch(r -> r.withoutMessage() == 1), "the historical row is counted, not queued");
        assertEquals(30, lifecycle.countDocuments(eq("kind", "EDIT")));
        assertEquals(30, lifecycle.countDocuments(eq("withdrawn", true)));
        var marked = offers.find(eq("_id", offer)).first();
        assertTrue(marked.getBoolean("endMarkDone"));
        assertEquals(1, marked.getInteger("endMarkSkipped"));
    }

    @Test void oneReminderPerOfferAndChannelUnderARace() throws Exception {
        String offer = approved("2");
        remindersOn("200000000000000010", 24);
        var delivery = deliveries.find(eq("_id", sent(offer, "200000000000000010", "900000000000000010", now.minus(Duration.ofDays(2))))).first();
        var created = race(8, () -> jobs.planReminder(offer, delivery, 24, now));
        assertEquals(1, created.stream().filter(b -> b).count());
        var planner = new FreebieLifecyclePlanner(repository, subscriptions);
        var planned = race(6, () -> planner.reminders(now));
        assertEquals(0, planned.stream().mapToInt(Integer::intValue).sum(), "the planner finds the existing reminder");
        assertEquals(1, lifecycle.countDocuments(eq("_id", FreebieLifecycleStore.remindId(offer, "200000000000000010"))));
    }

    @Test void plannerQueuesOnlyDueEligibleReminders() {
        String offer = approved("3");
        remindersOn("200000000000000010", 24);
        sent(offer, "200000000000000010", "900000000000000010", now.minus(Duration.ofDays(2)));
        String late = approved("4");
        remindersOn("200000000000000011", 24); // moves the section: channel 10 no longer has reminders
        sent(late, "200000000000000011", "900000000000000011", now.minus(Duration.ofHours(11)));
        sent(offer, "200000000000000011", "900000000000000012", now.minus(Duration.ofDays(2)));
        var planner = new FreebieLifecyclePlanner(repository, subscriptions);
        assertEquals(0, planner.reminders(now.minusSeconds(1)), "not due yet");
        assertEquals(1, planner.reminders(now), "only the long-running alert in the channel that asks for reminders");
        assertNotNull(lifecycle.find(eq("_id", FreebieLifecycleStore.remindId(offer, "200000000000000011"))).first());
        assertNull(lifecycle.find(eq("_id", FreebieLifecycleStore.remindId(late, "200000000000000011"))).first(), "posted under 12 h before");
        offers.updateOne(eq("_id", late), set("endsAtEstimated", true));
        assertEquals(0, planner.reminders(now));
    }

    @Test void expiredLeasesAreRecoveredAndStaleOutcomesRejected() {
        String offer = approved("5");
        sent(offer, "200000000000000010", "900000000000000010", now);
        jobs.planEdits(offer, false, now);
        var first = jobs.claim(now, Duration.ofMinutes(3)).orElseThrow();
        assertEquals(Kind.EDIT, first.kind());
        assertTrue(jobs.claim(now, Duration.ofMinutes(3)).isEmpty(), "leased jobs are not claimed twice");
        Instant later = now.plus(Duration.ofMinutes(4));
        assertFalse(jobs.done(first, "x", later), "an expired lease cannot record an outcome even before recovery");
        assertEquals(1, jobs.recover(later));
        var doc = lifecycle.find().first();
        assertEquals("PENDING", doc.getString("state"));
        assertTrue(doc.getBoolean("verifyFirst"));
        var second = jobs.claim(later, Duration.ofMinutes(3)).orElseThrow();
        assertEquals(2, second.attempts());
        assertFalse(jobs.skipped(first, FreebieLifecycle.Skip.UNKNOWN_MESSAGE, later), "the old token is fenced out");
        assertTrue(jobs.done(second, "x", later));
        assertFalse(jobs.retry(second, later, false, "late", later), "a final job never reopens");
        assertEquals("DONE", lifecycle.find().first().getString("state"));
        assertEquals(0, jobs.recover(later.plus(Duration.ofHours(1))));
    }

    @Test void endingCancelsPendingRemindersButNotFinishedOnes() {
        String offer = approved("6");
        remindersOn("200000000000000010", 24);
        var d1 = deliveries.find(eq("_id", sent(offer, "200000000000000010", "900000000000000010", now.minus(Duration.ofDays(2))))).first();
        var d2 = deliveries.find(eq("_id", sent(offer, "200000000000000011", "900000000000000011", now.minus(Duration.ofDays(2))))).first();
        jobs.planReminder(offer, d1, 24, now);
        jobs.planReminder(offer, d2, 24, now);
        var claimed = jobs.claim(now, Duration.ofMinutes(3)).orElseThrow();
        assertTrue(jobs.done(claimed, "posted", now));
        assertEquals(1, jobs.cancelReminders(offer, now));
        assertEquals(Set.of("DONE", "CANCELLED"), new HashSet<>(lifecycle.distinct("state", String.class).into(new ArrayList<>())));
    }

    @Test void endCandidatesFollowTheTriggerRules() {
        String stopped = approved("7"), estimated = approved("8"), recent = approved("9");
        assertTrue(repository.stopByOwner(stopped, FreebieOffer.State.APPROVED, "333644367539470337", now).isPresent());
        offers.updateOne(eq("_id", estimated), combine(set("endsAtEstimated", true), set("state", "COMPLETED")));
        Instant after = end.plusSeconds(60);
        var found = jobs.endCandidates(after, 50);
        assertEquals(Set.of(stopped, recent), new HashSet<>(found.stream().map(c -> c.offer().id()).toList()),
                "an estimated end date alone is not an end");
        assertTrue(found.stream().filter(c -> c.offer().id().equals(stopped)).allMatch(FreebieLifecycleStore.EndCandidate::ownerStopped));
        jobs.endMarked(stopped, 0, after);
        assertFalse(jobs.endCandidates(after, 50).stream().anyMatch(c -> c.offer().id().equals(stopped)));
        offers.updateOne(eq("_id", estimated), set("gone", true));
        assertTrue(jobs.endCandidates(after, 50).stream().anyMatch(c -> c.offer().id().equals(estimated)), "leaving the feed does end it");
    }

    @Test void retentionKeepsDeliveriesWithOpenJobsAndPurgesFinishedJobs() {
        String offer = approved("10");
        Instant old = Instant.parse("2026-06-01T00:00:00Z"), purgeAt = Instant.parse("2027-06-01T00:00:00Z");
        String keep = sent(offer, "200000000000000010", "900000000000000010", old);
        String go = sent(offer, "200000000000000011", "900000000000000011", old);
        offers.updateOne(eq("_id", offer), combine(set("state", "COMPLETED"), set("gone", true), set("summaryPosted", true),
                set("updatedAt", Date.from(old)), set("lastSeenAt", Date.from(old))));
        jobs.planEdits(offer, false, old);
        var a = jobs.claim(old, Duration.ofMinutes(3)).orElseThrow();
        var b = jobs.claim(old, Duration.ofMinutes(3)).orElseThrow();
        var finished = a.deliveryId().equals(go) ? a : b;
        var open = finished == a ? b : a;
        assertTrue(jobs.done(finished, "x", old));
        assertTrue(jobs.release(open, purgeAt.plusSeconds(3600), old), "still queued at purge time");

        var result = repository.retention().purge(purgeAt, 90, FreebieRetention.Limits.DEFAULT);
        assertEquals(1, result.deliveries());
        assertNotNull(deliveries.find(eq("_id", keep)).first(), "an alert with an open edit keeps its message ID");
        assertNull(deliveries.find(eq("_id", go)).first());
        assertNull(lifecycle.find(eq("_id", finished.id())).first(), "finished jobs follow the window");
        assertNotNull(lifecycle.find(eq("_id", open.id())).first(), "open jobs are never purged");
        assertEquals(0, result.offers(), "the offer waits for its remaining delivery");
        assertTrue(repository.retention().last().isPresent());
    }

    @Test void theReminderSettingSurvivesOtherSavesAndDefaultsToOff() {
        var plain = new FreebieSubscriptions.Subscription(guild, "200000000000000010", FreebieStore.EPIC, Optional.empty());
        subscriptions.save(plain, "1", now);
        assertEquals(0, subscriptions.find(guild, "200000000000000010", FreebieStore.EPIC).orElseThrow().reminderHours());
        subscriptions.setSection(guild, FreebieSection.MAJOR_PC, "200000000000000010", EnumSet.of(FreebieStore.EPIC),
                Optional.empty(), 6, "1", now);
        subscriptions.save(new FreebieSubscriptions.Subscription(guild, "200000000000000010", FreebieStore.EPIC, Optional.of("3")), "1", now);
        assertEquals(6, subscriptions.find(guild, "200000000000000010", FreebieStore.EPIC).orElseThrow().reminderHours(),
                "a role change elsewhere keeps the section's reminder");
        subscriptions.setSection(guild, FreebieSection.MAJOR_PC, "200000000000000010", EnumSet.of(FreebieStore.EPIC),
                Optional.empty(), null, "1", now);
        assertEquals(6, subscriptions.find(guild, "200000000000000010", FreebieStore.EPIC).orElseThrow().reminderHours());
        assertEquals(Set.of("200000000000000010"), subscriptions.withReminders(FreebieStore.EPIC).keySet());
        assertThrows(IllegalArgumentException.class, () -> subscriptions.setSection(guild, FreebieSection.MAJOR_PC,
                "200000000000000010", EnumSet.of(FreebieStore.EPIC), Optional.empty(), 12, "1", now));
    }
}
