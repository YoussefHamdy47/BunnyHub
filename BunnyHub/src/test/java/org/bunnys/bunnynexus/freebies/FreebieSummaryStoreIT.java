package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoDatabase;
import net.dv8tion.jda.api.JDA;
import org.bson.Document;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreebieSummaryStoreIT {
    LocalReplicaSet replica;
    MongoDatabase db;
    FreebieRepository repository;
    FreebieSummaryStore store;
    final Instant now = Instant.parse("2026-09-25T12:00:00Z");
    final Duration lease = Duration.ofMinutes(3);
    final String channel = "100000000000000099";

    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() {
        db = replica.client.getDatabase("summary_it_" + UUID.randomUUID().toString().replace("-", ""));
        repository = new FreebieRepository(db);
        repository.installIndexes();
        store = repository.summaries();
    }

    FreebieOffer terminal(String id, FreebieOffer.State state) {
        repository.recordSeen(FreebieRepositoryIT.candidate(id, "PC, Steam"), now);
        repository.transition(FreebieOffer.idFor(id), FreebieOffer.State.PENDING, FreebieOffer.State.APPROVED, "owner", now);
        return repository.transition(FreebieOffer.idFor(id), FreebieOffer.State.APPROVED, state, null, now).orElseThrow();
    }
    Document saved(FreebieOffer offer) { return db.getCollection(FreebieRepository.OFFERS).find(eq("_id", offer.id())).first(); }

    @Test void claimIsNotSuccessAndFailedAttemptRemainsRecoverable() {
        var offer = terminal("1", FreebieOffer.State.COMPLETED);
        var claim = store.claim(offer.id(), channel, now, lease).orElseThrow();
        assertFalse(claim.verifyFirst());
        assertFalse(saved(offer).getBoolean("summaryPosted", false));
        assertNull(saved(offer).getString("summaryMessageId"));
        assertTrue(store.retry(claim, now));
        assertTrue(store.awaiting(now.plusSeconds(119), 10).isEmpty());
        var next = store.claim(offer.id(), channel, now.plusSeconds(120), lease).orElseThrow();
        assertTrue(next.verifyFirst());
        assertTrue(store.sent(next, "accepted", now.plusSeconds(121)));
        assertEquals("accepted", saved(offer).getString("summaryMessageId"));
        assertTrue(saved(offer).getBoolean("summaryPosted"));
        assertTrue(store.awaiting(now.plusSeconds(1000), 10).isEmpty());
    }

    @Test void restartAndLeaseExpiryRecoverBothTerminalStates() {
        for (var state : List.of(FreebieOffer.State.COMPLETED, FreebieOffer.State.STOPPED)) {
            var offer = terminal(state.name(), state);
            var first = store.claim(offer.id(), channel, now, lease).orElseThrow();
            // Crash either before send or after acceptance but before the success write: same durable state.
            var restarted = new FreebieRepository(db).summaries();
            assertTrue(restarted.claim(offer.id(), channel, now.plusSeconds(179), lease).isEmpty());
            var recovered = restarted.claim(offer.id(), "100000000000000098", now.plusSeconds(180), lease).orElseThrow();
            assertTrue(recovered.verifyFirst());
            assertEquals(channel, recovered.channelId(), "uncertain sends keep their destination after configuration changes");
            assertNotEquals(first.token(), recovered.token());
            assertFalse(restarted.sent(first, "stale", now.plusSeconds(181)));
            assertFalse(restarted.retry(first, now.plusSeconds(181)));
            assertTrue(restarted.sent(recovered, "history-result", now.plusSeconds(181)));
            assertTrue(new FreebieRepository(db).summaries().claim(offer.id(), channel, now.plusSeconds(1000), lease).isEmpty());
        }
    }

    @Test void expiredCallbacksCannotRecordSuccessEvenBeforeAnotherClaim() {
        var offer = terminal("1", FreebieOffer.State.STOPPED);
        var claim = store.claim(offer.id(), channel, now, lease).orElseThrow();
        assertFalse(store.owns(claim, now.plus(lease)));
        assertFalse(store.sent(claim, "late", now.plus(lease)));
        assertFalse(store.retry(claim, now.plus(lease)));
        assertFalse(saved(offer).getBoolean("summaryPosted", false));
    }

    @Test void concurrentClaimsHaveOnlyOneWinner() throws Exception {
        var offer = terminal("1", FreebieOffer.State.COMPLETED);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            List<Future<Optional<FreebieSummaryStore.Claim>>> results = new ArrayList<>();
            for (int i = 0; i < 16; i++) results.add(pool.submit(() -> {
                start.await();
                return new FreebieRepository(db).summaries().claim(offer.id(), channel, now, lease);
            }));
            start.countDown();
            int wins = 0;
            for (var result : results) if (result.get(30, TimeUnit.SECONDS).isPresent()) wins++;
            assertEquals(1, wins);
        }
    }

    @Test void onlyUnfinishedDueTerminalOffersAreRecovered() {
        var completed = terminal("1", FreebieOffer.State.COMPLETED);
        var stopped = terminal("2", FreebieOffer.State.STOPPED);
        var legacy = terminal("3", FreebieOffer.State.COMPLETED);
        db.getCollection(FreebieRepository.OFFERS).updateOne(eq("_id", legacy.id()), set("summaryPosted", true));
        repository.recordSeen(FreebieRepositoryIT.candidate("4", "PC, Steam"), now);
        assertEquals(Set.of(completed.id(), stopped.id()), new HashSet<>(store.awaiting(now, 10).stream().map(FreebieOffer::id).toList()));
        assertTrue(store.claim("gamerpower:4", channel, now, lease).isEmpty());
        assertTrue(store.claim(legacy.id(), channel, now, lease).isEmpty());
        store.claim(completed.id(), channel, now, lease).orElseThrow();
        assertEquals(List.of(stopped.id()), store.awaiting(now, 10).stream().map(FreebieOffer::id).toList());
        assertEquals(2, store.awaiting(now.plus(lease), 10).size());
    }

    @Test void persistedReviewDestinationOverridesConfiguration() {
        var offer = terminal("1", FreebieOffer.State.STOPPED);
        db.getCollection(FreebieRepository.OFFERS).updateOne(eq("_id", offer.id()), set("reviewChannelId", "100000000000000098"));
        var claim = store.claim(offer.id(), channel, now, lease).orElseThrow();
        assertEquals("100000000000000098", claim.channelId());
        assertEquals(claim.channelId(), saved(offer).getString("summaryChannelId"));
    }

    @Test void housekeepingRecoversCompletedAndStoppedOffersAfterRestart() throws Exception {
        var completed = terminal("1", FreebieOffer.State.COMPLETED);
        var stopped = terminal("2", FreebieOffer.State.STOPPED);
        var client = mock(JDA.class);
        when(client.getStatus()).thenReturn(JDA.Status.CONNECTED);
        var restarted = new FreebieRepository(db);
        var system = new FreebieSystem(new FreebieConfig(channel, Set.of("333644367539470337"), 10), restarted,
                new FreebieSubscriptions(db), () -> client, Clock.fixed(now, ZoneOffset.UTC), new GamerPowerFeed());
        try {
            var housekeeping = FreebieSystem.class.getDeclaredMethod("housekeeping");
            housekeeping.setAccessible(true);
            housekeeping.invoke(system);
            var executorField = FreebieSystem.class.getDeclaredField("sendingThread");
            executorField.setAccessible(true);
            ((ExecutorService) executorField.get(system)).submit(() -> {}).get(10, TimeUnit.SECONDS);
            for (var offer : List.of(completed, stopped)) {
                assertTrue(saved(offer).getBoolean("summaryAttempted", false), "housekeeping must discover " + offer.state());
                assertFalse(saved(offer).getBoolean("summaryPosted", false));
                assertNotNull(saved(offer).getDate("summaryNextAttemptAt"));
            }
        } finally { system.close(); }
    }
}
