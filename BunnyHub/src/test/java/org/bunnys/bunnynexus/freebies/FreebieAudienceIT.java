package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;
import static org.junit.jupiter.api.Assertions.*;

/** Batch 3 against a real isolated replica set: DM jobs, the subscriber cap, the daily cap, catch-up and retention. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreebieAudienceIT {
    LocalReplicaSet replica;
    FreebieRepository repository;
    FreebieSubscriptions subscriptions;
    MongoCollection<Document> offers, deliveries, dmJobs, dmSubscribers;
    final Instant end = Instant.parse("2026-10-03T01:59:00Z"), now = end.minus(Duration.ofDays(2));
    final String guild = "100000000000000001";

    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() {
        MongoDatabase db = replica.client.getDatabase("audience_" + UUID.randomUUID().toString().replace("-", ""));
        repository = new FreebieRepository(db); repository.installIndexes();
        subscriptions = new FreebieSubscriptions(db); subscriptions.installIndexes();
        offers = db.getCollection(FreebieRepository.OFFERS);
        deliveries = db.getCollection(FreebieRepository.DELIVERIES);
        dmJobs = db.getCollection(FreebieDmJobs.COLLECTION);
        dmSubscribers = db.getCollection(FreebieDmSubscribers.COLLECTION);
    }

    String approved(String id) {
        repository.recordSeen(FreebieRepositoryIT.candidate(id, "PC, Steam"), now.minus(Duration.ofDays(1)));
        String offerId = FreebieOffer.idFor(id);
        offers.updateOne(eq("_id", offerId), combine(set("state", "APPROVED"), set("decidedBy", "333644367539470337"),
                set("fanoutDone", true), set("updatedAt", Date.from(now))));
        return offerId;
    }
    FreebieDmService dms(int cap, Instant at) {
        var config = new FreebieConfig("100000000000000099", "100000000000000099", Set.of("333644367539470337"), 10, 90, cap);
        return new FreebieDmService(config, repository, Clock.fixed(at, ZoneOffset.UTC));
    }

    @Test void concurrentPlanningCreatesOneDmJobPerOfferAndUser() throws Exception {
        String offer = approved("1");
        var users = new ArrayList<String>();
        for (int i = 0; i < 40; i++) users.add("4000000000000000" + (10 + i));
        var planned = FreebieLifecycleIT.race(8, () -> repository.dmJobs().plan(offer, users, now));
        assertEquals(40, planned.stream().mapToInt(Integer::intValue).sum());
        assertEquals(40, dmJobs.countDocuments(eq("offerId", offer)));
        assertEquals(40, dmJobs.distinct("userId", String.class).into(new ArrayList<>()).size());
    }

    @Test void expiredDmLeasesAreRecoveredAndStaleOutcomesFenced() {
        String offer = approved("2");
        repository.dmJobs().plan(offer, List.of("400000000000000001"), now);
        var first = repository.dmJobs().claim(now, Duration.ofMinutes(3)).orElseThrow();
        assertTrue(repository.dmJobs().claim(now, Duration.ofMinutes(3)).isEmpty());
        Instant later = now.plus(Duration.ofMinutes(4));
        assertFalse(repository.dmJobs().sent(first, "m", later), "an expired lease cannot record an outcome");
        assertEquals(1, repository.dmJobs().recover(later));
        assertTrue(dmJobs.find().first().getBoolean("verifyFirst"), "the next attempt checks the DM history first");
        var second = repository.dmJobs().claim(later, Duration.ofMinutes(3)).orElseThrow();
        assertFalse(repository.dmJobs().failed(first, "stale", later));
        assertTrue(repository.dmJobs().sent(second, "m", later));
        assertFalse(repository.dmJobs().retry(second, later, true, "late", later), "a sent DM never reopens");
        assertEquals("SENT", dmJobs.find().first().getString("state"));
    }

    @Test void theSubscriberCapHoldsUnderConcurrentSubscribes() throws Exception {
        int cap = 7;
        var next = new AtomicInteger();
        var results = FreebieLifecycleIT.race(20, () -> {
            String user = "4000000000000001" + (10 + next.getAndIncrement());
            return dms(cap, now).subscribe(user, EnumSet.of(FreebieSection.MAJOR_PC), m -> true);
        });
        assertEquals(cap, results.stream().filter(r -> r == FreebieDmService.Subscribed.SUBSCRIBED).count(), results.toString());
        assertEquals(20 - cap, results.stream().filter(r -> r == FreebieDmService.Subscribed.FULL).count());
        assertEquals(cap, dmSubscribers.countDocuments());
        assertEquals(cap, repository.dmSubscribers().active());
        // Lowering the cap leaves existing subscribers alone; they can still edit, newcomers are refused.
        String existing = dmSubscribers.find().first().getString("_id");
        assertEquals(FreebieDmService.Subscribed.EDITED, dms(2, now).subscribe(existing, EnumSet.of(FreebieSection.CONSOLE), m -> fail("no DM")));
        assertEquals(FreebieDmService.Subscribed.FULL, dms(2, now).subscribe("400000000000000999", EnumSet.of(FreebieSection.CONSOLE), m -> true));
        assertEquals(cap, repository.dmSubscribers().active());
        // Unsubscribing frees a slot.
        assertTrue(dms(cap, now).unsubscribe(existing));
        assertEquals(FreebieDmService.Subscribed.SUBSCRIBED, dms(cap, now).subscribe("400000000000000999", EnumSet.of(FreebieSection.MAJOR_PC), m -> true));
    }

    @Test void aRefusedConsentDmLeavesNoSubscriptionAndAnAbandonedOneExpires() {
        assertEquals(FreebieDmService.Subscribed.DM_REFUSED, dms(5, now).subscribe("400000000000000001", EnumSet.of(FreebieSection.MOBILE), m -> false));
        assertEquals(0, dmSubscribers.countDocuments());
        assertEquals(FreebieDmSubscribers.Reserve.RESERVED, repository.dmSubscribers().reserve("400000000000000002", EnumSet.of(FreebieSection.MOBILE), 1, now));
        assertEquals(FreebieDmSubscribers.Reserve.FULL, repository.dmSubscribers().reserve("400000000000000003", EnumSet.of(FreebieSection.MOBILE), 1, now));
        assertTrue(repository.dmSubscribers().following(FreebieSection.MOBILE).isEmpty(), "a pending consent is not a subscription");
        assertEquals(1, repository.dmSubscribers().expirePending(now.plus(Duration.ofMinutes(11))));
        assertEquals(FreebieDmSubscribers.Reserve.RESERVED, repository.dmSubscribers().reserve("400000000000000003", EnumSet.of(FreebieSection.MOBILE), 1, now.plus(Duration.ofMinutes(11))));
    }

    @Test void unsubscribeRemovesOnlyThatUser() {
        var service = dms(10, now);
        service.subscribe("400000000000000001", EnumSet.of(FreebieSection.MAJOR_PC), m -> true);
        service.subscribe("400000000000000002", EnumSet.of(FreebieSection.MAJOR_PC), m -> true);
        assertTrue(service.unsubscribe("400000000000000002"));
        assertEquals(List.of("400000000000000001"), repository.dmSubscribers().following(FreebieSection.MAJOR_PC));
        assertFalse(service.unsubscribe("400000000000000002"));
        assertEquals(1, dmSubscribers.countDocuments());
    }

    @Test void atMostFiveGameDmsPerUserPerUtcDay() {
        assertEquals(FreebieDmService.Subscribed.SUBSCRIBED, dms(5, now).subscribe("400000000000000001", EnumSet.of(FreebieSection.MAJOR_PC), m -> true));
        var subs = repository.dmSubscribers();
        Instant day = Instant.parse("2026-10-01T23:00:00Z");
        for (int i = 0; i < FreebieDm.DAILY_CAP; i++) assertTrue(subs.countToday("400000000000000001", day));
        assertFalse(subs.countToday("400000000000000001", day.plusSeconds(1800)));
        assertTrue(subs.countToday("400000000000000001", Instant.parse("2026-10-02T00:00:01Z")), "a new UTC day starts over");
        assertFalse(subs.countToday("400000000000000999", day), "not subscribed");
    }

    @Test void unsubscribingOrStoppingCancelsOnlyUnsentDms() {
        String offer = approved("3"), other = approved("4");
        repository.dmJobs().plan(offer, List.of("400000000000000001", "400000000000000002"), now);
        repository.dmJobs().plan(other, List.of("400000000000000001"), now);
        var claimed = repository.dmJobs().claim(now, Duration.ofMinutes(3)).orElseThrow();
        assertTrue(repository.dmJobs().sent(claimed, "m", now));
        long stopped = repository.dmJobs().cancelForOffer(offer, now);
        long gone = repository.dmJobs().cancelForUser("400000000000000001", FreebieDm.Cancel.CANNOT_DM, now);
        assertEquals(2, stopped + gone);
        assertEquals(1, dmJobs.countDocuments(eq("state", "SENT")));
        assertEquals(2, dmJobs.countDocuments(eq("state", "CANCELLED")));
    }

    @Test void catchUpAfterASaveNeverDuplicatesAnExistingDelivery() throws Exception {
        String offer = approved("5");
        var fanned = new FreebieSubscriptions.Subscription(guild, "200000000000000010", FreebieStore.STEAM, Optional.empty());
        subscriptions.save(fanned, "1", now);
        repository.planDeliveries(repository.offer(offer).orElseThrow(), List.of(fanned), now);
        subscriptions.save(new FreebieSubscriptions.Subscription(guild, "200000000000000011", FreebieStore.STEAM, Optional.empty()), "1", now);
        var system = new FreebieSystem(new FreebieConfig("100000000000000099", Set.of("333644367539470337"), 10),
                repository, subscriptions, () -> null, Clock.fixed(now, ZoneOffset.UTC), new GamerPowerFeed());
        try {
            assertEquals(new FreebieSystem.CatchUp(1, 0), system.catchUpChannel(guild, "200000000000000010", s -> true),
                    "the channel that already got it gets nothing");
            var raced = FreebieLifecycleIT.race(6, () -> system.catchUpChannel(guild, "200000000000000011", s -> true).queued());
            assertEquals(1, raced.stream().mapToInt(Integer::intValue).sum(), "one delivery however often it runs");
            assertEquals(2, deliveries.countDocuments(eq("offerId", offer)));
            var row = deliveries.find(eq("_id", offer + "|200000000000000011")).first();
            assertTrue(row.getBoolean("catchUp"));
        } finally { system.close(); }
    }

    // ------------------------------------------------------------------ claim paths (item 3) and old rows (item 1)

    String row(String offerId, String channel, Document extra) {
        // Exactly the fields FreebieRepository.delivery() wrote before this version (catchUp only when true).
        var doc = new Document("_id", offerId + "|" + channel).append("schemaVersion", 1).append("offerId", offerId)
                .append("guildId", guild).append("channelId", channel).append("roleId", null)
                .append("state", "PENDING").append("attempts", 0).append("verifyFirst", false);
        doc.putAll(extra);
        deliveries.insertOne(doc.append("nextAttemptAt", Date.from(now.minusSeconds(60))).append("createdAt", Date.from(now.minusSeconds(60))));
        return doc.getString("_id");
    }

    @Test void aCatchUpRowWaitsWhileAFreshRowIsDueAndIsClaimedByTheLowPriorityPathOtherwise() {
        String offer = approved("8");
        String catchUp = row(offer, "200000000000000020", new Document("catchUp", true));
        assertTrue(repository.claim(now, Duration.ofMinutes(3)).isEmpty(), "fresh claim never takes a catch-up row");
        assertEquals("PENDING", deliveries.find(eq("_id", catchUp)).first().getString("state"));
        String fresh = row(offer, "200000000000000021", new Document());
        assertEquals(fresh, repository.claim(now, Duration.ofMinutes(3)).orElseThrow().id(), "the due first post goes first");
        assertTrue(repository.claim(now, Duration.ofMinutes(3)).isEmpty());
        assertEquals("PENDING", deliveries.find(eq("_id", catchUp)).first().getString("state"), "still waiting, not lost");
        var claimed = repository.claimCatchUp(now, Duration.ofMinutes(3)).orElseThrow();
        assertEquals(catchUp, claimed.id());
        assertTrue(claimed.catchUp());
        assertTrue(repository.claimCatchUp(now, Duration.ofMinutes(3)).isEmpty());
    }

    @Test void rowsWrittenByThePreviousVersionAreClaimedByExactlyOnePath() {
        String offer = approved("9");
        // Pending catch-up from before the upgrade, a blocked (uncertain) one, a plain fan-out row and an explicit false.
        var oldCatchUp = row(offer, "200000000000000030", new Document("catchUp", true));
        var blockedCatchUp = row(offer, "200000000000000031", new Document("catchUp", true).append("state", "BLOCKED")
                .append("blockedReason", "UNCERTAIN").append("verifyFirst", true));
        var fanOut = row(offer, "200000000000000032", new Document());
        var explicitFalse = row(offer, "200000000000000033", new Document("catchUp", false));
        Set<String> fresh = new HashSet<>(), low = new HashSet<>();
        for (var c = repository.claim(now, Duration.ofMinutes(3)); c.isPresent(); c = repository.claim(now, Duration.ofMinutes(3))) fresh.add(c.get().id());
        for (var c = repository.claimCatchUp(now, Duration.ofMinutes(3)); c.isPresent(); c = repository.claimCatchUp(now, Duration.ofMinutes(3))) low.add(c.get().id());
        assertEquals(Set.of(fanOut, explicitFalse), fresh);
        assertEquals(Set.of(oldCatchUp, blockedCatchUp), low);
        assertEquals(4, deliveries.countDocuments(eq("state", "SENDING")), "every old row was claimed, none twice and none left behind");
        assertTrue(Collections.disjoint(fresh, low));
    }

    // ------------------------------------------------------------------ fairness (item 2)

    @Test void aDueReminderIsClaimedBeforeAnEarlierEditBacklog() {
        String offer = approved("10");
        var lifecycle = repository.lifecycle();
        for (int i = 0; i < 30; i++) deliveries.insertOne(new Document("_id", offer + "|ch" + i).append("offerId", offer)
                .append("guildId", guild).append("channelId", "ch" + i).append("state", "SENT").append("messageId", "m" + i)
                .append("finishedAt", Date.from(now.minus(Duration.ofDays(2)))));
        lifecycle.planEdits(offer, false, now.minus(Duration.ofHours(1))); // due an hour earlier than the reminder
        var delivery = deliveries.find(eq("_id", offer + "|ch7")).first();
        assertTrue(lifecycle.planReminder(offer, delivery, 24, now));
        var first = lifecycle.claim(now, Duration.ofMinutes(3)).orElseThrow();
        assertEquals(FreebieLifecycle.Kind.REMIND, first.kind(), "time-sensitive reminders go before older edits");
        assertEquals(FreebieLifecycle.Kind.EDIT, lifecycle.claim(now, Duration.ofMinutes(3)).orElseThrow().kind());
    }

    @Test void retentionKeepsOpenDmJobsAndPurgesFinishedOnes() {
        String offer = approved("6");
        Instant old = Instant.parse("2026-06-01T00:00:00Z"), purgeAt = Instant.parse("2027-06-01T00:00:00Z");
        offers.updateOne(eq("_id", offer), combine(set("state", "COMPLETED"), set("gone", true), set("summaryPosted", true),
                set("updatedAt", Date.from(old)), set("lastSeenAt", Date.from(old)), set("endsAt", Date.from(old))));
        repository.dmJobs().plan(offer, List.of("400000000000000001", "400000000000000002"), old);
        var done = repository.dmJobs().claim(old, Duration.ofMinutes(3)).orElseThrow();
        assertTrue(repository.dmJobs().sent(done, "m", old));
        repository.retention().purge(purgeAt, 90, FreebieRetention.Limits.DEFAULT);
        assertNull(dmJobs.find(eq("_id", done.id())).first(), "finished DM jobs follow the window");
        assertEquals(1, dmJobs.countDocuments(eq("state", "PENDING")), "open DM jobs are never purged");
        assertEquals("COMPLETED", offers.find(eq("_id", offer)).first().getString("state"), "the offer waits for its open DM");
        dmJobs.updateMany(eq("state", "PENDING"), combine(set("state", "CANCELLED"), set("finishedAt", Date.from(old))));
        repository.retention().purge(purgeAt, 90, FreebieRetention.Limits.DEFAULT);
        assertEquals(0, dmJobs.countDocuments());
        assertEquals("PURGED", offers.find(eq("_id", offer)).first().getString("state"));
    }

    @Test void fanOutPlansDmsForFollowersOfTheSectionOnly() {
        String offer = approved("7");
        offers.updateOne(eq("_id", offer), set("fanoutDone", false));
        var service = dms(10, now);
        service.subscribe("400000000000000001", EnumSet.of(FreebieSection.MAJOR_PC), m -> true);
        service.subscribe("400000000000000002", EnumSet.of(FreebieSection.CONSOLE), m -> true);
        var system = new FreebieSystem(new FreebieConfig("100000000000000099", "100000000000000099", Set.of("333644367539470337"), 10, 90, 10),
                repository, subscriptions, () -> null, Clock.fixed(now, ZoneOffset.UTC), new GamerPowerFeed());
        try {
            system.fanOut(repository.offer(offer).orElseThrow());
            system.fanOut(repository.offer(offer).orElseThrow());
        } finally { system.close(); }
        assertEquals(List.of("400000000000000001"), dmJobs.distinct("userId", String.class).into(new ArrayList<>()));
        assertEquals(1, dmJobs.countDocuments());
        assertEquals(new FreebieDmJobs.Totals(0, 0, 0, 0, 1), repository.dmJobs().totals(offer));
        assertEquals(0, repository.counts(offer).open(), "DM jobs never hold the completion summary back");
    }
}
