package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoDatabase;
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
    FreebieSubscriptions subscriptions;
    String dbName;
    final Instant now = Instant.parse("2026-09-25T12:00:00Z");

    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() {
        dbName = "freebie_it_" + UUID.randomUUID().toString().replace("-", "");
        MongoDatabase db = replica.client.getDatabase(dbName);
        repository = new FreebieRepository(db);
        subscriptions = new FreebieSubscriptions(db);
        repository.installIndexes();
        subscriptions.installIndexes();
    }

    static GamerPowerIntake.Candidate candidate(String id, String platforms) {
        return new GamerPowerIntake.Candidate(id, "Game " + id + " Giveaway", "desc", "1. Claim", platforms,
                LocalDateTime.parse("2026-09-20T10:00:00"), Optional.of(LocalDateTime.parse("2026-10-02T23:59:00")),
                URI.create("https://www.gamerpower.com/game-" + id), URI.create("https://www.gamerpower.com/open/game-" + id),
                Instant.parse("2026-09-25T12:00:00Z"), Optional.empty(), Optional.of("$9.99"));
    }
    void subscribe(String guild, String channel, FreebieStore store) {
        assertNotEquals(FreebieSubscriptions.SaveResult.LIMIT_REACHED,
                subscriptions.save(new FreebieSubscriptions.Subscription(guild, channel, store, Optional.empty()), "1", now));
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
        assertTrue(repository.reviews().claim(now).isPresent());
        assertTrue(repository.reviews().claim(now).isEmpty());
        assertTrue(repository.reviews().claim(now.plus(Duration.ofMinutes(3))).isPresent());
        var claim = repository.reviews().claim(now.plusSeconds(400)).orElseThrow();
        repository.reviews().destination(claim, "100000000000000099", now.plusSeconds(400));
        repository.reviews().sent(claim, "100000000000000099", "1234567890123456789", now.plusSeconds(400));
        assertTrue(repository.reviews().claim(now.plus(Duration.ofMinutes(10))).isEmpty());
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
        assertEquals(new FreebieSubscriptions.Audience(2, 2), subscriptions.audience(FreebieStore.EPIC));
        assertEquals(2, repository.planDeliveries(offer, subscriptions.forStore(offer.store()), now));
        assertEquals(0, repository.planDeliveries(offer, subscriptions.forStore(offer.store()), now));
        assertEquals(2, repository.counts(offer.id()).open());
    }

    @Test void parallelWorkersClaimEachDeliveryExactlyOnce() throws Exception {
        for (int i = 0; i < 60; i++) subscribe(String.valueOf(100000000000000000L + i), String.valueOf(200000000000000000L + i), FreebieStore.EPIC);
        var offer = approved("8");
        assertEquals(60, repository.planDeliveries(offer, subscriptions.forStore(offer.store()), now));
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
        repository.planDeliveries(offer, subscriptions.forStore(offer.store()), now);
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

        assertTrue(repository.markRetry(retry, FreebieFailure.TRANSIENT, "timeout", now.plus(Duration.ofMinutes(10)), true, now.plus(Duration.ofMinutes(4))));
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
        repository.planDeliveries(offer, subscriptions.forStore(offer.store()), now);
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
        assertTrue(repository.summaries().claim(offer.id(), "100000000000000099", now, Duration.ofMinutes(3)).isPresent());
        assertTrue(repository.summaries().claim(offer.id(), "100000000000000099", now, Duration.ofMinutes(3)).isEmpty(), "summary lease excludes another worker");
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
        // The public /free-games list: every launcher, same approval rules, newest first and bounded.
        assertTrue(repository.recordSeen(candidate("34", "PC, Steam"), now));
        repository.transition("gamerpower:34", FreebieOffer.State.PENDING, FreebieOffer.State.APPROVED, "1", now);
        assertEquals(Set.of("gamerpower:30", "gamerpower:31", "gamerpower:34"), new HashSet<>(repository
                .liveApproved(Optional.empty(), now, 10).stream().map(FreebieOffer::id).toList()));
        assertEquals(1, repository.liveApproved(Optional.empty(), now, 1).size());
        assertEquals(List.of("gamerpower:34"), repository.liveApproved(Optional.of(FreebieStore.STEAM), now, 10)
                .stream().map(FreebieOffer::id).toList());

        var sub = new FreebieSubscriptions.Subscription("100000000000000009", "200000000000000009", FreebieStore.EPIC, Optional.empty());
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

    @Test void subscriptionsAreLimitedPerServerAndRemovable() {
        for (int i = 0; i < FreebieSubscriptions.MAX_CHANNELS_PER_GUILD; i++)
            subscribe("100000000000000001", String.valueOf(200000000000000000L + i), FreebieStore.EPIC);
        assertEquals(FreebieSubscriptions.SaveResult.LIMIT_REACHED, subscriptions.save(new FreebieSubscriptions.Subscription(
                "100000000000000001", "299999999999999999", FreebieStore.EPIC, Optional.empty()), "1", now));
        // More launchers in a channel already in use are still allowed at the channel limit.
        assertEquals(FreebieSubscriptions.SaveResult.CREATED, subscriptions.save(new FreebieSubscriptions.Subscription(
                "100000000000000001", "200000000000000000", FreebieStore.STEAM, Optional.empty()), "1", now));
        // Updating an existing one is still allowed at the limit.
        assertEquals(FreebieSubscriptions.SaveResult.UPDATED, subscriptions.save(new FreebieSubscriptions.Subscription(
                "100000000000000001", "200000000000000000", FreebieStore.EPIC, Optional.of("300000000000000000")), "1", now));
        assertEquals(1, subscriptions.remove("100000000000000001", "200000000000000000", Optional.of(FreebieStore.EPIC)));
        assertEquals(FreebieSubscriptions.MAX_CHANNELS_PER_GUILD, subscriptions.forGuild("100000000000000001").size());
    }

    @Test void launcherChecklistReplacesAChannelsExactSet() {
        String guild = "100000000000000007", channel = "200000000000000007";
        subscribe(guild, channel, FreebieStore.EPIC);
        subscribe(guild, channel, FreebieStore.XBOX);
        subscribe(guild, "200000000000000008", FreebieStore.XBOX); // another channel is untouched
        var change = subscriptions.setChannelStores(guild, channel, EnumSet.of(FreebieStore.EPIC, FreebieStore.STEAM, FreebieStore.GOG),
                Optional.of("300000000000000007"), "1", now);
        assertEquals(FreebieSubscriptions.SaveResult.UPDATED, change.result());
        assertEquals(EnumSet.of(FreebieStore.STEAM, FreebieStore.GOG), change.added());
        assertEquals(EnumSet.of(FreebieStore.XBOX), change.removed());
        var saved = subscriptions.forGuild(guild);
        assertEquals(4, saved.size());
        assertTrue(saved.stream().filter(s -> s.channelId().equals(channel))
                .allMatch(s -> s.roleId().equals(Optional.of("300000000000000007"))), "one role for the whole set");
        assertEquals(1, subscriptions.audience(FreebieStore.XBOX).channels());

        // Every launcher fits in one channel; a sixth channel does not.
        for (int i = 0; i < FreebieSubscriptions.MAX_CHANNELS_PER_GUILD - 2; i++)
            subscribe(guild, String.valueOf(210000000000000000L + i), FreebieStore.EPIC);
        assertEquals(FreebieSubscriptions.SaveResult.UPDATED, subscriptions.setChannelStores(guild, channel,
                EnumSet.allOf(FreebieStore.class), Optional.empty(), "1", now).result());
        assertEquals(FreebieSubscriptions.SaveResult.LIMIT_REACHED, subscriptions.setChannelStores(guild, "299999999999999998",
                EnumSet.of(FreebieStore.EPIC), Optional.empty(), "1", now).result());
        assertThrows(IllegalArgumentException.class, () -> subscriptions.setChannelStores(guild, channel,
                EnumSet.noneOf(FreebieStore.class), Optional.empty(), "1", now));
    }

    @Test void fanOutThatFinishesAfterAStopLeavesNothingToSend() {
        subscribe("100000000000000011", "200000000000000011", FreebieStore.EPIC);
        subscribe("100000000000000012", "200000000000000012", FreebieStore.EPIC);
        var offer = approved("60");
        // The owner's stop lands while the approval's fan-out is still inserting rows: nothing to cancel yet.
        repository.transition(offer.id(), FreebieOffer.State.APPROVED, FreebieOffer.State.STOPPED, "1", now).orElseThrow();
        var config = new FreebieConfig("100000000000000099", Set.of("333644367539470337"), 10);
        var system = new FreebieSystem(config, repository, subscriptions, () -> null, Clock.fixed(now, ZoneOffset.UTC), new GamerPowerFeed());
        try { system.fanOut(offer); } finally { system.close(); }
        var counts = repository.counts(offer.id());
        assertEquals(0, counts.open(), "late rows must not be sent for a stopped offer");
        assertEquals(2, counts.cancelled());
        assertTrue(repository.offer(offer.id()).orElseThrow().fanoutDone());
    }

    @Test void concurrentNewChannelsNeverExceedTheServerLimit() throws Exception {
        String guild = "100000000000000013";
        for (int i = 0; i < FreebieSubscriptions.MAX_CHANNELS_PER_GUILD - 1; i++)
            subscribe(guild, String.valueOf(220000000000000000L + i), FreebieStore.EPIC);
        int racers = 8;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(racers);
        try {
            List<Future<FreebieSubscriptions.SaveResult>> results = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                String channel = String.valueOf(230000000000000000L + i);
                results.add(pool.submit(() -> {
                    start.await();
                    return subscriptions.save(new FreebieSubscriptions.Subscription(guild, channel, FreebieStore.STEAM, Optional.empty()), "1", now);
                }));
            }
            start.countDown();
            long created = 0;
            for (var result : results) if (result.get(30, TimeUnit.SECONDS) == FreebieSubscriptions.SaveResult.CREATED) created++;
            long channels = subscriptions.forGuild(guild).stream().map(FreebieSubscriptions.Subscription::channelId).distinct().count();
            assertTrue(channels <= FreebieSubscriptions.MAX_CHANNELS_PER_GUILD, channels + " channels");
            assertEquals(channels - (FreebieSubscriptions.MAX_CHANNELS_PER_GUILD - 1), created, "every CREATED answer is still saved");
        } finally { pool.shutdownNow(); }
    }

    @Test void priorityLaunchersAreReviewedFirstAndRememberTheirChannel() {
        assertTrue(repository.recordSeen(candidate("70", "PC, Itch.io"), now));
        assertTrue(repository.recordSeen(candidate("71", "PC, Steam"), now.plusSeconds(60)));
        // itch.io was found first, but the Steam game is claimed for review before it.
        var first = repository.reviews().claim(now.plusSeconds(120)).orElseThrow();
        assertEquals(FreebieStore.STEAM, first.offer().store());
        assertEquals(FreebieStore.ITCHIO, repository.reviews().claim(now.plusSeconds(120)).orElseThrow().offer().store());
        assertTrue(repository.reviews().claim(now.plusSeconds(120)).isEmpty());
        repository.reviews().destination(first, "100000000000000098", now.plusSeconds(120));
        repository.reviews().sent(first, "100000000000000098", "1234567890123456789", now.plusSeconds(120));
        assertEquals(Optional.of("100000000000000098"), repository.offer(first.offer().id()).orElseThrow().reviewChannelId());
    }

    @Test void sectionSaveMovesOnlyThatSectionAndCountsChannelsAfterTheMove() {
        String guild = "100000000000000014", general = "240000000000000000", pc = "240000000000000001";
        subscribe(guild, general, FreebieStore.EPIC);
        subscribe(guild, general, FreebieStore.STEAM);
        subscribe(guild, general, FreebieStore.ANDROID);
        assertEquals(FreebieSubscriptions.SaveResult.CREATED, subscriptions.setSection(guild, FreebieSection.MAJOR_PC, pc,
                EnumSet.of(FreebieStore.EPIC, FreebieStore.GOG, FreebieStore.ANDROID), Optional.of("250000000000000000"), "1", now));
        Map<FreebieStore, FreebieSubscriptions.Subscription> byStore = new EnumMap<>(FreebieStore.class);
        subscriptions.forGuild(guild).forEach(s -> byStore.put(s.store(), s));
        // Steam was unticked, GOG added, Epic moved; the mobile launcher (another section) is ignored and untouched.
        assertEquals(EnumSet.of(FreebieStore.EPIC, FreebieStore.GOG, FreebieStore.ANDROID), byStore.keySet());
        assertEquals(pc, byStore.get(FreebieStore.EPIC).channelId());
        assertEquals(Optional.of("250000000000000000"), byStore.get(FreebieStore.GOG).roleId());
        assertEquals(general, byStore.get(FreebieStore.ANDROID).channelId());
        subscribe(guild, "240000000000000002", FreebieStore.GOG);
        assertEquals(new FreebieSubscriptions.Audience(2, 1), subscriptions.audience(FreebieStore.GOG), "two channels, one server");
        assertEquals(new FreebieSubscriptions.Audience(0, 0), subscriptions.audience(FreebieStore.SWITCH));
        assertThrows(IllegalArgumentException.class, () -> subscriptions.setSection(guild, FreebieSection.CONSOLE, pc,
                EnumSet.of(FreebieStore.EPIC), Optional.empty(), "1", now));

        // Full server: a section that had a channel to itself can still move, because its old slot is freed.
        String full = "100000000000000015";
        for (int i = 0; i < FreebieSubscriptions.MAX_CHANNELS_PER_GUILD - 1; i++)
            subscribe(full, String.valueOf(260000000000000000L + i), FreebieStore.PLAYSTATION);
        subscribe(full, "269999999999999999", FreebieStore.STEAM);
        assertEquals(FreebieSubscriptions.SaveResult.CREATED, subscriptions.setSection(full, FreebieSection.MAJOR_PC,
                "270000000000000000", EnumSet.of(FreebieStore.STEAM), Optional.empty(), "1", now));
        assertEquals(FreebieSubscriptions.SaveResult.LIMIT_REACHED, subscriptions.setSection(full, FreebieSection.MOBILE,
                "270000000000000001", EnumSet.of(FreebieStore.IOS), Optional.empty(), "1", now));
        assertEquals(FreebieSubscriptions.MAX_CHANNELS_PER_GUILD,
                subscriptions.forGuild(full).stream().map(FreebieSubscriptions.Subscription::channelId).distinct().count());

        assertEquals(FreebieSubscriptions.MAX_CHANNELS_PER_GUILD - 1, subscriptions.clearSection(full, FreebieSection.CONSOLE));
        assertEquals(List.of(FreebieStore.STEAM), subscriptions.forGuild(full).stream().map(FreebieSubscriptions.Subscription::store).toList());
    }
}

