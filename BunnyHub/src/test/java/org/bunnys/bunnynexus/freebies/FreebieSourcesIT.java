package org.bunnys.bunnynexus.freebies;

import org.junit.jupiter.api.*;
import org.bson.Document;
import com.mongodb.client.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreebieSourcesIT {
    LocalReplicaSet replica;
    MongoDatabase db;
    FreebieRepository repository;
    MongoCollection<Document> offers;
    final Instant now = EpicIntakeTest.NOW;
    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() {
        db = replica.client.getDatabase("sources_it_" + UUID.randomUUID().toString().replace("-", ""));
        repository = new FreebieRepository(db); repository.installIndexes(); offers = db.getCollection(FreebieRepository.OFFERS);
    }
    FreebieFeed.Candidate epic() throws Exception { return EpicIntakeTest.candidate(); }
    FreebieFeed.Candidate gp() throws Exception {
        var e = epic();
        return new FreebieFeed.Candidate(FreebieFeed.Provider.GAMERPOWER, "123", e.store(), e.platforms(), e.title(),
                "description", "claim", Optional.empty(), Optional.empty(), java.net.URI.create("https://www.gamerpower.com/open/out-of-sight"),
                java.net.URI.create("https://www.gamerpower.com/out-of-sight"), Optional.empty(), e.endsAt(), "Not specified by source");
    }
    String parent() throws Exception { repository.recordSeen(gp(), now); return "gamerpower:123"; }
    String token() throws Exception { return FreebieMessages.reference(epic().identity()); }
    void same() throws Exception {
        parent(); repository.recordSeen(epic(), now);
        assertTrue(repository.sources().resolve("gamerpower:123", token(), "same", "owner", now));
        repository.recordSeen(epic(), now);
    }
    @Test void concurrentDiscoveryReservesOneReviewAndOneOwnerQuestion() throws Exception {
        var g = gp(); var e = epic();
        try (var pool = Executors.newFixedThreadPool(12)) {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 30; i++) { var c = i % 2 == 0 ? g : e; tasks.add(() -> repository.recordSeen(c, now)); }
            int created = 0; for (var f : pool.invokeAll(tasks)) if (f.get()) created++;
            assertEquals(1, created);
        }
        assertEquals(1, offers.countDocuments());
        var row = offers.find().first(); assertEquals(2, row.getList("sourceAliases", String.class).size());
        assertEquals(1, row.get("sourceCandidates", Document.class).size());
        assertEquals(1, repository.offersIn(FreebieOffer.State.PENDING, 10).size());
    }
    @Test void confirmingAliasPreservesApprovalRevisionReferencesAndNeverReapproves() throws Exception {
        String id = parent(); repository.transition(id, FreebieOffer.State.PENDING, FreebieOffer.State.APPROVED, "original-owner", now);
        var before = repository.offer(id).orElseThrow(); repository.recordSeen(epic(), now);
        assertTrue(repository.sources().resolve(id, token(), "same", "owner", now));
        assertFalse(repository.sources().resolve(id, token(), "separate", "other-owner", now));
        repository.recordSeen(epic(), now);
        assertEquals(before, repository.offer(id).orElseThrow());
        assertEquals(1, offers.countDocuments());
    }
    @Test void independentSourcesNeedTwoCompleteMissesEachAndSurviveRestart() throws Exception {
        same();
        assertTrue(repository.markMissing(FreebieFeed.Provider.GAMERPOWER, Set.of()).isEmpty());
        assertTrue(repository.markMissing(FreebieFeed.Provider.GAMERPOWER, Set.of()).isEmpty());
        repository = new FreebieRepository(db);
        assertFalse(repository.offer("gamerpower:123").orElseThrow().gone());
        assertTrue(repository.markMissing(FreebieFeed.Provider.EPIC, Set.of()).isEmpty());
        assertEquals(1, repository.markMissing(FreebieFeed.Provider.EPIC, Set.of()).size());
        assertTrue(repository.markMissing(FreebieFeed.Provider.EPIC, Set.of()).isEmpty());
    }
    @Test void unresolvedAndIgnoredMatchesNeverGainLivenessAuthority() throws Exception {
        parent(); repository.recordSeen(epic(), now);
        assertTrue(repository.markMissing(FreebieFeed.Provider.EPIC, Set.of()).isEmpty());
        repository.markMissing(Set.of()); assertEquals(1, repository.markMissing(Set.of()).size());
        assertTrue(repository.sources().resolve("gamerpower:123", token(), "ignore", "owner", now));
        repository.recordSeen(epic(), now); assertTrue(repository.offer("gamerpower:123").orElseThrow().gone());
    }
    @Test void rejectionOwnerStopAndPurgeRemainAuthoritativeThroughAliasRediscovery() throws Exception {
        for (String state : List.of("REJECTED", "STOPPED", "PURGED")) {
            fresh(); same();
            offers.updateOne(eq("_id", "gamerpower:123"), combine(set("state", state), set("stoppedByOwner", true)));
            repository.recordSeen(epic(), now); repository.recordSeen(gp(), now);
            assertEquals(state, offers.find().first().getString("state"));
            assertTrue(repository.reviews().claim(now).isEmpty());
            assertTrue(repository.liveApproved(Optional.empty(), now, 10).isEmpty());
        }
    }
    @Test void purgePreservesAliasesAndMatchReservation() throws Exception {
        same(); offers.updateOne(eq("_id", "gamerpower:123"), set("state", "REJECTED"));
        assertEquals(1, repository.retention().purge(now.plus(Duration.ofDays(200)), 90, FreebieRetention.Limits.DEFAULT).offers());
        var tombstone = offers.find().first(); assertEquals("PURGED", tombstone.getString("state"));
        assertEquals(2, tombstone.getList("sourceAliases", String.class).size()); assertNotNull(tombstone.getString("matchKey"));
        assertFalse(repository.recordSeen(epic(), now.plus(Duration.ofDays(201)))); assertEquals(1, offers.countDocuments());
    }
    @Test void boundedLegacyBackfillResumesWithoutChangingIdsOrStates() {
        for (int i = 1; i <= 205; i++) offers.insertOne(new Document("_id", "gamerpower:" + i).append("schemaVersion", 1)
                .append("source", "gamerpower").append("sourceId", "" + i).append("state", "PURGED"));
        assertFalse(repository.sources().backfill()); assertEquals(200, offers.countDocuments(exists("sourceAliases")));
        repository = new FreebieRepository(db); assertTrue(repository.sources().backfill());
        assertEquals(205, offers.countDocuments(eq("state", "PURGED")));
    }
    @Test void legacyOfferKeepsOriginalRecoveryIdentity() throws Exception {
        var old = FreebieSourceStore.fresh(gp(), now); old.remove("sourceAliases"); old.remove("evidence");
        offers.insertOne(old); repository.recordSeen(epic(), now);
        assertEquals("gamerpower:123", offers.find().first().getString("_id")); assertEquals(1, offers.countDocuments());
    }
    @Test void separateDecisionIsRestartableAndStillRequiresApproval() throws Exception {
        parent(); repository.recordSeen(epic(), now);
        assertTrue(repository.sources().resolve("gamerpower:123", token(), "separate", "owner", now));
        repository = new FreebieRepository(db); assertTrue(repository.recordSeen(epic(), now));
        assertFalse(repository.recordSeen(epic(), now)); assertEquals(2, offers.countDocuments());
        var child = repository.offer(FreebieSourceStore.canonicalId(epic())).orElseThrow();
        assertEquals(FreebieOffer.State.PENDING, child.state());
        repository.markMissing(FreebieFeed.Provider.EPIC, Set.of(child.id(), epic().identity()));
        repository.markMissing(FreebieFeed.Provider.EPIC, Set.of(child.id(), epic().identity()));
        assertFalse(repository.offer(child.id()).orElseThrow().gone());
    }
    @Test void editionWordsPreventAutomaticCollision() throws Exception {
        parent(); var c = epic(); var different = new FreebieFeed.Candidate(c.provider(), c.sourceId(), c.store(), c.platforms(),
                c.title() + " Deluxe Edition", c.description(), c.instructions(), c.worth(), c.image(), c.claimPage(), c.sourcePage(), c.startsAt(), c.endsAt(), c.region());
        assertTrue(repository.recordSeen(different, now)); assertEquals(2, offers.countDocuments());
    }
    @Test void knownStoreSuffixProposesAnOwnerDecisionButKeepsEditions() throws Exception {
        var g = gp(); var advertised = new FreebieFeed.Candidate(g.provider(), g.sourceId(), g.store(), g.platforms(),
                g.title() + " (Epic Games) Giveaway", g.description(), g.instructions(), g.worth(), g.image(), g.claimPage(), g.sourcePage(), g.startsAt(), g.endsAt(), g.region());
        repository.recordSeen(advertised, now); assertFalse(repository.recordSeen(epic(), now));
        assertTrue(repository.sources().conflict().isPresent()); assertEquals(1, offers.countDocuments());
    }
    @Test void uniqueAliasIndexRejectsASecondCanonicalOwner() throws Exception {
        parent(); var duplicate = FreebieSourceStore.fresh(gp(), now); duplicate.put("_id", "different-id");
        assertThrows(com.mongodb.MongoWriteException.class, () -> offers.insertOne(duplicate));
    }
    @Test void capturedFeedsConvergeDespiteGamerPowerExpiryGrace() throws Exception {
        try (var input = getClass().getResourceAsStream("/gamerpower/captured-source-comparison-2026-10-13.json")) {
            var batch = new GamerPowerIntake().decode(200, input.readAllBytes(), now);
            var game = batch.candidates().stream().filter(c -> c.sourceItemId().equals("3806")).findFirst().orElseThrow();
            assertTrue(repository.recordSeen(game, now));
            assertEquals(Instant.parse("2026-10-16T01:59:00Z"), repository.offer("gamerpower:3806").orElseThrow().endsAt().orElseThrow());
            assertFalse(repository.recordSeen(epic(), now));
            assertEquals(1, offers.countDocuments());
            assertTrue(repository.sources().conflict().isPresent());
        }
    }
    @Test void sameProviderIdentityDecisionDoesNotEraseFreshLiveness() throws Exception {
        parent(); var g = gp();
        var second = new FreebieFeed.Candidate(g.provider(), "124", g.store(), g.platforms(), g.title(), g.description(),
                g.instructions(), g.worth(), g.image(), g.claimPage(), g.sourcePage(), g.startsAt(), g.endsAt(), g.region());
        repository.recordSeen(second, now);
        assertTrue(repository.sources().resolve("gamerpower:123", FreebieMessages.reference(second.identity()), "same", "owner", now));
        assertEquals(0, offers.find().first().get("evidence", Document.class).get("gamerpower", Document.class).getInteger("missing"));
    }
    @Test void aliasReservationBoundIsAtomicUnderConcurrentDiscovery() throws Exception {
        parent(); var g = gp(); var aliases = new ArrayList<String>(); aliases.add(g.identity());
        for (int i = 0; i < 62; i++) aliases.add("reserved:" + i);
        offers.updateOne(eq("_id", "gamerpower:123"), set("sourceAliases", aliases));
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(20)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String id = "" + (1000 + i);
                futures.add(pool.submit(() -> {
                    gate.await();
                    var candidate = new FreebieFeed.Candidate(g.provider(), id, g.store(), g.platforms(), g.title(), g.description(),
                            g.instructions(), g.worth(), g.image(), g.claimPage(), g.sourcePage(), g.startsAt(), g.endsAt(), g.region());
                    try { repository.recordSeen(candidate, now); } catch (IllegalStateException full) { /* Bounded refusal. */ }
                    return null;
                }));
            }
            gate.countDown(); for (var future : futures) future.get();
        }
        assertEquals(64, offers.find().first().getList("sourceAliases", String.class).size());
    }
    @Test void concurrentRetentionCannotEraseANewSourceReservation() throws Exception {
        parent(); offers.updateOne(eq("_id", "gamerpower:123"), set("state", "REJECTED"));
        var intercepted = org.mockito.Mockito.spy(offers);
        var delegated = org.mockito.Mockito.mock(MongoDatabase.class, org.mockito.AdditionalAnswers.delegatesTo(db));
        org.mockito.Mockito.when(delegated.getCollection(FreebieRepository.OFFERS)).thenReturn(intercepted);
        var newcomer = epic();
        org.mockito.Mockito.doAnswer(call -> {
            repository.recordSeen(newcomer, now);
            return offers.replaceOne(call.getArgument(0, org.bson.conversions.Bson.class), call.getArgument(1, Document.class));
        }).when(intercepted).replaceOne(org.mockito.ArgumentMatchers.any(org.bson.conversions.Bson.class), org.mockito.ArgumentMatchers.any(Document.class));
        var retention = new FreebieRetention(delegated);
        assertEquals(0, retention.purge(now.plus(Duration.ofDays(200)), 90, FreebieRetention.Limits.DEFAULT).offers());
        assertEquals(2, offers.find().first().getList("sourceAliases", String.class).size());
    }
}
