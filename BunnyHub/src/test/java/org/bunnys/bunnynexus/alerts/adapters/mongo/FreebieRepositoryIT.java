package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.client.MongoDatabase;
import org.bunnys.bunnynexus.alerts.adapters.providers.GamerPowerIntake;
import org.bunnys.bunnynexus.freebies.*;
import org.junit.jupiter.api.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real MongoDB checks for the freebie pipeline. Run with -Dtest=FreebieRepositoryIT -Dbunny.test.mongod=... */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreebieRepositoryIT {
    LocalReplicaSet replica;
    FreebieRepository repository;
    String dbName;
    final Instant now = Instant.parse("2026-09-25T12:00:00Z");

    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() {
        dbName = "freebie_it_" + UUID.randomUUID().toString().replace("-", "");
        MongoDatabase db = replica.client.getDatabase(dbName);
        repository = new FreebieRepository(db);
        repository.installIndexes();
    }

    static GamerPowerIntake.Candidate candidate(String id, String platforms) {
        return new GamerPowerIntake.Candidate(id, "Game " + id + " Giveaway", "desc", "1. Claim", platforms,
                LocalDateTime.parse("2026-09-20T10:00:00"), Optional.of(LocalDateTime.parse("2026-10-02T23:59:00")),
                URI.create("https://www.gamerpower.com/game-" + id), URI.create("https://www.gamerpower.com/open/game-" + id),
                Instant.parse("2026-09-25T12:00:00Z"), Optional.empty(), Optional.of("$9.99"));
    }
    void subscribe(String guild, String channel, FreebieStore store) {
        assertNotEquals(FreebieRepository.SaveResult.LIMIT_REACHED,
                repository.saveSubscription(new FreebieRepository.Subscription(guild, channel, store, Optional.empty()), "1", now));
    }
    FreebieOffer approved(String id) {
        assertTrue(repository.recordSeen(candidate(id, "PC, Epic Games Store"), now));
        return repository.transition(FreebieOffer.idFor(id), FreebieOffer.State.PENDING, FreebieOffer.State.APPROVED, "333644367539470337", now).orElseThrow();
    }

    @Test void discoveryRecordsOnceAndEndsOnlyAfterTwoCompleteMisses() {
        assertTrue(repository.recordSeen(candidate("1", "PC, Epic Games Store"), now));
        assertFalse(repository.recordSeen(candidate("1", "PC, Epic Games Store"), now));
        var offer = repository.offer("gamerpower:1").orElseThrow();
        assertEquals(FreebieOffer.State.PENDING, offer.state());
        assertEquals(FreebieStore.EPIC, offer.store());
        assertTrue(repository.markMissing(Set.of()).isEmpty());
        assertEquals(1, repository.markMissing(Set.of()).size());
        // Seen again resets the counter.
        repository.recordSeen(candidate("1", "PC, Epic Games Store"), now);
        assertTrue(repository.markMissing(Set.of()).isEmpty());
    }

    @Test void reviewPostIsLeasedSoTwoWorkersCannotBothPost() {
        repository.recordSeen(candidate("1", "PC, Steam"), now);
        assertTrue(repository.claimReviewPost(now).isPresent());
        assertTrue(repository.claimReviewPost(now).isEmpty());
        assertTrue(repository.claimReviewPost(now.plus(Duration.ofMinutes(3))).isPresent());
        repository.setReviewMessage("gamerpower:1", "1234567890123456789");
        assertTrue(repository.claimReviewPost(now.plus(Duration.ofMinutes(10))).isEmpty());
    }

    @Test void concurrentApprovalClicksApproveExactlyOnce() throws Exception {
        repository.recordSeen(candidate("1", "PC, Steam"), now);
        var pool = Executors.newFixedThreadPool(8);
        var wins = new AtomicInteger();
        var start = new CountDownLatch(1);
        List<Future<?>> all = new ArrayList<>();
        for (int i = 0; i < 16; i++) all.add(pool.submit(() -> {
            start.await();
            if (repository.transition("gamerpower:1", FreebieOffer.State.PENDING, FreebieOffer.State.APPROVED, "1", now).isPresent())
                wins.incrementAndGet();
            return null;
        }));
        start.countDown();
        for (var f : all) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(1, wins.get());
        assertTrue(repository.transition("gamerpower:1", FreebieOffer.State.PENDING, FreebieOffer.State.REJECTED, "1", now).isEmpty());
    }

    @Test void fanOutTargetsOnlyMatchingLauncherAndIsIdempotent() {
        subscribe("100000000000000001", "200000000000000001", FreebieStore.EPIC);
        subscribe("100000000000000001", "200000000000000002", FreebieStore.STEAM);
        subscribe("100000000000000002", "200000000000000003", FreebieStore.EPIC);
        var offer = approved("7");
        assertEquals(new FreebieRepository.Audience(2, 2), repository.audience(FreebieStore.EPIC));
        assertEquals(2, repository.planDeliveries(offer, now));
        assertEquals(0, repository.planDeliveries(offer, now));
        assertEquals(2, repository.counts(offer.id()).open());
    }

    @Test void parallelWorkersClaimEachDeliveryExactlyOnce() throws Exception {
        for (int i = 0; i < 60; i++) subscribe(String.valueOf(100000000000000000L + i), String.valueOf(200000000000000000L + i), FreebieStore.EPIC);
        var offer = approved("8");
        assertEquals(60, repository.planDeliveries(offer, now));
        var claimed = ConcurrentHashMap.<String>newKeySet();
        var duplicates = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(8);
        List<Future<?>> all = new ArrayList<>();
        for (int t = 0; t < 8; t++) all.add(pool.submit(() -> {
            Optional<FreebieRepository.Claimed> c;
            while ((c = repository.claim(now, Duration.ofMinutes(3))).isPresent())
                if (!claimed.add(c.get().id())) duplicates.incrementAndGet();
            return null;
        }));
        for (var f : all) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(60, claimed.size());
        assertEquals(0, duplicates.get());
    }

    @Test void leaseTokenFencesOutcomesAndCrashRecoveryForcesVerification() {
        subscribe("100000000000000001", "200000000000000001", FreebieStore.EPIC);
        var offer = approved("9");
        repository.planDeliveries(offer, now);
        var job = repository.claim(now, Duration.ofMinutes(3)).orElseThrow();
        assertEquals(1, job.attempts());
        assertFalse(job.verifyFirst());
        var stale = new FreebieRepository.Claimed(job.id(), job.offerId(), job.guildId(), job.channelId(), job.roleId(), 1, false, "not-the-owner");
        assertFalse(repository.markSent(stale, "1", now));

        // Process "crashes": lease expires, recovery requires a history check before any resend.
        assertEquals(1, repository.recoverExpiredLeases(now.plus(Duration.ofMinutes(4))));
        assertFalse(repository.markSent(job, "1", now), "the old lease must no longer be able to record an outcome");
        var retry = repository.claim(now.plus(Duration.ofMinutes(4)), Duration.ofMinutes(3)).orElseThrow();
        assertTrue(retry.verifyFirst());
        assertEquals(2, retry.attempts());

        assertTrue(repository.markRetry(retry, FreebieFailure.TRANSIENT, "timeout", now.plus(Duration.ofMinutes(10)), true));
        assertTrue(repository.claim(now.plus(Duration.ofMinutes(5)), Duration.ofMinutes(3)).isEmpty(), "not due yet");
        var third = repository.claim(now.plus(Duration.ofMinutes(11)), Duration.ofMinutes(3)).orElseThrow();
        assertTrue(repository.markSent(third, "1234567890123456789", now));
        assertEquals(new FreebieRepository.Counts(1, 0, 0, 0), repository.counts(offer.id()));
    }

    @Test void stopCancelsOnlyUnsentAndFailuresAreReported() {
        subscribe("100000000000000001", "200000000000000001", FreebieStore.EPIC);
        subscribe("100000000000000002", "200000000000000002", FreebieStore.EPIC);
        subscribe("100000000000000003", "200000000000000003", FreebieStore.EPIC);
        var offer = approved("10");
        repository.planDeliveries(offer, now);
        var a = repository.claim(now, Duration.ofMinutes(3)).orElseThrow();
        var b = repository.claim(now, Duration.ofMinutes(3)).orElseThrow();
        repository.markSent(a, "1234567890123456789", now);
        repository.markFailed(b, FreebieFailure.MISSING_PERMISSIONS, "no send", now);
        assertEquals(1, repository.cancelPending(offer.id(), "stopped", now));
        assertEquals(new FreebieRepository.Counts(1, 1, 1, 0), repository.counts(offer.id()));
        var failures = repository.failures(offer.id(), 10);
        assertEquals(1, failures.size());
        assertEquals(FreebieFailure.MISSING_PERMISSIONS, failures.getFirst().reason());

        repository.transition(offer.id(), FreebieOffer.State.APPROVED, FreebieOffer.State.STOPPED, "1", now);
        assertTrue(repository.claimSummary(offer.id()));
        assertFalse(repository.claimSummary(offer.id()), "summary is posted once");
    }

    @Test void launcherCanOnlyBeCorrectedBeforeApproval() {
        repository.recordSeen(candidate("20", "PC, DRM-Free"), now);
        var changed = repository.changeStore("gamerpower:20", FreebieStore.GOG, now).orElseThrow();
        assertEquals(FreebieStore.GOG, changed.store());
        repository.transition("gamerpower:20", FreebieOffer.State.PENDING, FreebieOffer.State.APPROVED, "1", now);
        assertTrue(repository.changeStore("gamerpower:20", FreebieStore.EPIC, now).isEmpty());
        assertEquals(FreebieStore.GOG, repository.offer("gamerpower:20").orElseThrow().store());
    }

    @Test void lateSubscriberCatchUpOnlyGetsLiveApprovedGamesOnce() {
        var sent = approved("30");
        repository.transition(sent.id(), FreebieOffer.State.APPROVED, FreebieOffer.State.COMPLETED, null, now);
        approved("31");                                           // still sending: also eligible
        repository.recordSeen(candidate("32", "PC, Epic Games Store"), now); // never approved: never eligible
        var rejected = approved("33");
        repository.transition(rejected.id(), FreebieOffer.State.APPROVED, FreebieOffer.State.STOPPED, "1", now);
        var live = repository.liveApproved(FreebieStore.EPIC, now);
        assertEquals(Set.of("gamerpower:30", "gamerpower:31"), new HashSet<>(live.stream().map(FreebieOffer::id).toList()));
        assertTrue(repository.liveApproved(FreebieStore.EPIC, Instant.parse("2026-10-05T00:00:00Z")).isEmpty(), "ended by date");

        var sub = new FreebieRepository.Subscription("100000000000000009", "200000000000000009", FreebieStore.EPIC, Optional.empty());
        assertTrue(repository.planDelivery(live.getFirst(), sub, now));
        assertFalse(repository.planDelivery(live.getFirst(), sub, now), "never twice per channel");

        // A completed offer that disappears from the feed is no longer offered for catch-up.
        repository.markMissing(Set.of()); var gone = repository.markMissing(Set.of());
        assertTrue(gone.stream().anyMatch(o -> o.id().equals("gamerpower:30") && o.gone() && o.ended(now)));
        assertTrue(repository.liveApproved(FreebieStore.EPIC, now).isEmpty());
        assertTrue(repository.markMissing(Set.of()).isEmpty(), "reported once");
    }

    @Test void sendingPauseSurvivesRestartAndOverviewCounts() {
        assertFalse(repository.sendingPaused());
        repository.setSendingPaused(true, "1", now);
        assertTrue(new FreebieRepository(replica.client.getDatabase(dbName)).sendingPaused());
        repository.setSendingPaused(false, "1", now);
        assertFalse(repository.sendingPaused());
        repository.recordSeen(candidate("40", "PC, Steam"), now);
        assertEquals(1, repository.overview().pendingReviews());
    }

    @Test void serverOwnerIsNotifiedAtMostOncePerDay() {
        assertTrue(repository.claimServerNotice("100000000000000001", now));
        assertFalse(repository.claimServerNotice("100000000000000001", now.plus(Duration.ofHours(5))));
        assertTrue(repository.claimServerNotice("100000000000000002", now));
        assertTrue(repository.claimServerNotice("100000000000000001", now.plus(Duration.ofHours(25))));
    }

    @Test void subscriptionsAreLimitedPerServerAndRemovable() {
        for (int i = 0; i < FreebieRepository.MAX_SUBSCRIPTIONS_PER_GUILD; i++)
            subscribe("100000000000000001", String.valueOf(200000000000000000L + i), FreebieStore.EPIC);
        assertEquals(FreebieRepository.SaveResult.LIMIT_REACHED, repository.saveSubscription(new FreebieRepository.Subscription(
                "100000000000000001", "299999999999999999", FreebieStore.EPIC, Optional.empty()), "1", now));
        // Updating an existing one is still allowed at the limit.
        assertEquals(FreebieRepository.SaveResult.UPDATED, repository.saveSubscription(new FreebieRepository.Subscription(
                "100000000000000001", "200000000000000000", FreebieStore.EPIC, Optional.of("300000000000000000")), "1", now));
        assertEquals(1, repository.removeSubscriptions("100000000000000001", "200000000000000000", Optional.of(FreebieStore.EPIC)));
        assertEquals(FreebieRepository.MAX_SUBSCRIPTIONS_PER_GUILD - 1, repository.subscriptions("100000000000000001").size());
    }
}
