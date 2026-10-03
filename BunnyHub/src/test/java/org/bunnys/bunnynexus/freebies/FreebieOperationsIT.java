package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.*;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreebieOperationsIT {
    LocalReplicaSet replica;
    MongoDatabase db;
    FreebieRepository repository;
    final Instant now = Instant.parse("2026-09-25T12:00:00Z");
    final String guild = "100000000000000001", channel = "200000000000000001";
    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() {
        db = replica.client.getDatabase("operations_" + UUID.randomUUID().toString().replace("-", ""));
        repository = new FreebieRepository(db); repository.installIndexes();
    }

    String failure(String id, Instant at) {
        repository.recordSeen(FreebieRepositoryIT.candidate(id, "PC, Steam"), at);
        var offer = repository.transition("gamerpower:" + id, FreebieOffer.State.PENDING, FreebieOffer.State.APPROVED, "owner", at).orElseThrow();
        repository.planDelivery(offer, new FreebieSubscriptions.Subscription(guild, channel, FreebieStore.STEAM, Optional.empty()), at);
        var claim = repository.claim(at, Duration.ofMinutes(3)).orElseThrow();
        assertTrue(repository.markFailed(claim, FreebieFailure.MISSING_PERMISSIONS, "private internal detail", at));
        return claim.id();
    }
    Document notice() { return db.getCollection(FreebieRepository.NOTICES).find(eq("_id", guild)).first(); }
    Document delivery(String id) { return db.getCollection(FreebieRepository.DELIVERIES).find(eq("_id", id)).first(); }

    @Test void failedDeliverySurvivesCrashBeforeNotifierAndBeforeAcknowledgementReconciliation() {
        String id = failure("1", now);
        assertTrue(delivery(id).getBoolean("noticePending"));
        assertEquals(1, repository.health().snapshot(now).notices().total(), "show work before notice enqueueing");
        var restarted = new FreebieRepository(db).notices(); restarted.recover(now);
        var first = restarted.claim(now).orElseThrow();
        assertNull(notice().getDate("lastNoticeAt"));
        assertTrue(restarted.destination(first, "DM", "dm", "owner", now));
        assertTrue(restarted.claim(now.plusSeconds(179)).isEmpty());
        var next = new FreebieRepository(db).notices().claim(now.plusSeconds(180)).orElseThrow();
        assertTrue(next.verifyFirst()); assertEquals("dm", next.channelId());
        assertFalse(restarted.sent(first, "stale", now.plusSeconds(181)));
        assertFalse(restarted.retry(first, now.plusSeconds(181)));
        assertFalse(restarted.fallback(first, now.plusSeconds(181)));
        assertTrue(restarted.sent(next, "accepted", now.plusSeconds(181)));
        assertTrue(delivery(id).getBoolean("noticePending"));
        new FreebieRepository(db).notices().recover(now.plusSeconds(182));
        assertTrue(delivery(id).getBoolean("serverNotified"));
        assertFalse(delivery(id).getBoolean("noticePending"));
        assertTrue(notice().getBoolean("noticeReconciled"));
        assertTrue(restarted.claim(now.plusSeconds(1000)).isEmpty());
    }

    @Test void failuresRetryWithoutConsumingDailyNoticeAndDefiniteFallbackIsDurable() {
        failure("1", now); var store = repository.notices(); store.recover(now);
        var job = store.claim(now).orElseThrow();
        assertTrue(store.destination(job, "DM", "dm", "owner", now));
        assertTrue(store.fallback(job, now)); assertTrue(store.retry(job, now));
        assertNull(notice().getDate("lastNoticeAt"));
        assertTrue(store.claim(now.plusSeconds(119)).isEmpty());
        var retry = new FreebieRepository(db).notices().claim(now.plusSeconds(120)).orElseThrow();
        assertEquals("SYSTEM", retry.route()); assertFalse(retry.verifyFirst()); assertNull(retry.channelId());
    }

    @Test void serverOwnerIsNotifiedAtMostOncePerDayAndOldFailuresDoNotReplayTomorrow() {
        failure("1", now); var store = repository.notices(); store.recover(now);
        var job = store.claim(now).orElseThrow(); store.sent(job, "accepted", now); store.recover(now);
        String suppressed = failure("2", now.plusSeconds(10)); store.recover(now.plusSeconds(10));
        assertFalse(delivery(suppressed).getBoolean("noticePending"));
        assertTrue(delivery(suppressed).getBoolean("noticeSuppressed"));
        assertFalse(delivery(suppressed).getBoolean("serverNotified", false));
        assertTrue(store.claim(now.plus(Duration.ofHours(25))).isEmpty());
        String later = failure("3", now.plus(Duration.ofHours(25))); store.recover(now.plus(Duration.ofHours(25)));
        assertEquals(later, store.claim(now.plus(Duration.ofHours(25))).orElseThrow().deliveryId());
    }

    @Test void concurrentRecoveryAndClaimsCreateOneNotice() throws Exception {
        failure("1", now);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1); List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) futures.add(pool.submit(() -> {
                start.await(); var store = new FreebieRepository(db).notices(); store.recover(now); return store.claim(now).isPresent();
            }));
            start.countDown(); int won = 0;
            for (var future : futures) if (future.get(30, TimeUnit.SECONDS)) won++;
            assertEquals(1, won);
        }
    }

    @Test void legacyDailyNoticeReservationIsRespected() {
        db.getCollection(FreebieRepository.NOTICES).insertOne(new Document("_id", guild).append("lastNoticeAt", Date.from(now)));
        String id = failure("1", now.plusSeconds(10)); repository.notices().recover(now.plusSeconds(10));
        assertFalse(delivery(id).getBoolean("noticePending"));
        assertTrue(repository.notices().claim(now.plusSeconds(10)).isEmpty());
    }

    @Test void anotherFailureCannotReplaceAnUnfinishedNoticeForTheSameServer() {
        String first = failure("1", now); String second = failure("2", now.plusSeconds(1));
        var store = repository.notices(); store.recover(now.plusSeconds(1));
        var job = store.claim(now.plusSeconds(1)).orElseThrow(); assertEquals(first, job.deliveryId());
        store.recover(now.plusSeconds(122)); assertEquals(first, notice().getString("noticeDeliveryId"));
        assertTrue(store.sent(job, "accepted", now.plusSeconds(123)));
        store.recover(now.plusSeconds(243));
        assertFalse(delivery(second).getBoolean("noticePending")); assertTrue(delivery(second).getBoolean("noticeSuppressed"));
        assertTrue(store.claim(now.plusSeconds(243)).isEmpty());
    }

    @Test void concurrentReviewClaimsHaveOnlyOneWinner() throws Exception {
        repository.recordSeen(FreebieRepositoryIT.candidate("1", "PC, Steam"), now);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1); List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) futures.add(pool.submit(() -> {
                start.await(); return new FreebieRepository(db).reviews().claim(now).isPresent();
            }));
            start.countDown(); int won = 0;
            for (var future : futures) if (future.get(30, TimeUnit.SECONDS)) won++;
            assertEquals(1, won);
        }
    }

    @Test void reviewExpiryFencesDestinationAndSuccessAndDoesNotBypassApproval() {
        repository.recordSeen(FreebieRepositoryIT.candidate("1", "PC, Steam"), now);
        var first = repository.reviews().claim(now).orElseThrow();
        assertTrue(repository.reviews().destination(first, channel, now));
        assertFalse(repository.reviews().sent(first, channel, "late", now.plusSeconds(180)));
        var next = new FreebieRepository(db).reviews().claim(now.plusSeconds(180)).orElseThrow();
        assertEquals(channel, next.channelId()); assertTrue(next.verifyFirst());
        assertFalse(repository.reviews().destination(first, "different", now.plusSeconds(181)));
        assertTrue(repository.reviews().sent(next, channel, "found-in-history", now.plusSeconds(181)));
        assertEquals(FreebieOffer.State.PENDING, repository.offer(first.offer().id()).orElseThrow().state());
        assertTrue(repository.reviews().claim(now.plusSeconds(400)).isEmpty());
    }

    @Test void expiredOfferCannotBePostedByOldReviewWorker() {
        repository.recordSeen(FreebieRepositoryIT.candidate("1", "PC, Steam"), now);
        var claim = repository.reviews().claim(now).orElseThrow();
        repository.transition(claim.offer().id(), FreebieOffer.State.PENDING, FreebieOffer.State.EXPIRED, null, now);
        assertFalse(repository.reviews().destination(claim, channel, now));
        assertFalse(repository.reviews().owns(claim, now)); assertFalse(repository.reviews().sent(claim, channel, "late", now));
    }

    @Test void healthCountsQueuesAgesExpiredLeasesAndRecentFailuresWithoutMutating() {
        failure("1", now.minusSeconds(3600)); repository.notices().recover(now);
        repository.transition("gamerpower:1", FreebieOffer.State.APPROVED, FreebieOffer.State.COMPLETED, null, now.minusSeconds(1800));
        var summary = repository.summaries().claim("gamerpower:1", channel, now.minusSeconds(200), Duration.ofSeconds(180)).orElseThrow();
        repository.recordSeen(FreebieRepositoryIT.candidate("2", "PC, Steam"), now.minusSeconds(7200));
        var before = db.getCollection(FreebieRepository.OFFERS).find(eq("_id", "gamerpower:1")).first();
        var health = repository.health().snapshot(now);
        assertEquals(1, health.failedDay()); assertEquals(1, health.reviews().total());
        assertEquals(now.minusSeconds(7200), health.reviews().oldest());
        assertEquals(1, health.summaries().total()); assertEquals(1, health.summaries().expired());
        assertEquals(0, health.summaries().leased()); assertEquals(1, health.notices().total());
        assertEquals(channel, health.summariesWaiting().getFirst().channelId());
        assertEquals("MISSING_PERMISSIONS", health.failures().getFirst().reason());
        assertEquals(before, db.getCollection(FreebieRepository.OFFERS).find(eq("_id", summary.offer().id())).first());
    }

    @Test void healthCountsAllRowsButBoundsDetailsAndHandlesEmptyQueues() {
        assertEquals(0, repository.health().snapshot(now).summaries().total());
        for (int i = 0; i < 8; i++) {
            failure(Integer.toString(i), now);
            repository.transition("gamerpower:" + i, FreebieOffer.State.APPROVED, FreebieOffer.State.STOPPED, null, now);
        }
        var health = repository.health().snapshot(now);
        assertEquals(8, health.failedDay()); assertEquals(8, health.summaries().total());
        assertEquals(5, health.failures().size()); assertEquals(5, health.summariesWaiting().size());
        db.getCollection(FreebieRepository.DELIVERIES).updateMany(new Document(), set("finishedAt", Date.from(now.minus(Duration.ofDays(2)))));
        assertEquals(0, repository.health().snapshot(now).failedDay());
    }
}
