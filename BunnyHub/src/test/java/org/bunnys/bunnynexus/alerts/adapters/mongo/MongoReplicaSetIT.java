package org.bunnys.bunnynexus.alerts.adapters.mongo;

import org.bson.Document;
import org.bunnys.bunnynexus.alerts.application.*;
import org.bunnys.bunnynexus.alerts.domain.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import static com.mongodb.client.model.Filters.eq;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertSchema.*;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicitly run with -Dtest=MongoReplicaSetIT -Dbunny.test.mongod=... . Never part of offline default tests. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MongoReplicaSetIT {
    LocalReplicaSet replica;
    MongoAlertDatabase db;
    final OwnershipRepository.Source source = new OwnershipRepository.Source(new SourceId("gamerpower"), "games");
    final ConfigurationPolicy policy = new ConfigurationPolicy(new ConfigurationPolicy.Limits(2, 4, 2,
            Set.of(new StoreId("epic")), Set.of(new Market("EG")), Set.of(Topic.FREE_GAME)));

    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void provision() {
        db = replica.database();
        new MongoAlertSchema(db).install(30_000);
        new MongoCatalogSchema(db).install(100);
        new MongoOwnerReviewSchema(db).install();
        new MongoRuntimeSchema(db).install();
        new MongoConfigurationSchema(db).install();
        new MongoRuntimeSchema(db).provisionSources(Set.of(source));
        new MongoPollingSchema(db).install();
        new MongoPollingSchema(db).provision(Set.of(source));
    }

    @Test @Order(1) void migrationsAreRepeatableAndDetectIndexDrift() {
        new MongoAlertSchema(db).install(1); new MongoCatalogSchema(db).install(1);
        new MongoRuntimeSchema(db).install(); new MongoConfigurationSchema(db).install(); new MongoPollingSchema(db).install();
        assertEquals(30_000, AlertDocuments.number(db.collection(BACKLOG).find(eq("_id", "delivery")).first(), "maxJobs"));
        db.collection(DELIVERIES).dropIndex("due_jobs");
        assertThrows(IllegalStateException.class, () -> new MongoAlertSchema(db).verifyInstalled());
    }

    @Test @Order(2) void sourceOwnershipAndPollAttemptRacesHaveOneWinner() throws Exception {
        var owners = new MongoOwnershipRepository(db); var polls = new MongoPollRepository(db);
        var acquisitions = race(16, i -> owners.acquire(source, "owner-" + i, Duration.ofSeconds(30)));
        assertEquals(1, acquisitions.stream().filter(Optional::isPresent).count());
        var lease = acquisitions.stream().flatMap(Optional::stream).findFirst().orElseThrow();
        var begins = race(16, i -> polls.begin(lease, "attempt-" + i, Duration.ofSeconds(1)));
        assertEquals(1, begins.stream().filter(Boolean::booleanValue).count());
        int winner = begins.indexOf(true);
        var finishes = race(16, i -> polls.finish(lease, "attempt-" + winner, Duration.ofSeconds(1),
                new PollRepository.Completion(PollRepository.Outcome.PAUSED, 0, 0)));
        assertEquals(1, finishes.stream().filter(Boolean::booleanValue).count());
        assertTrue(owners.release(lease));
        var next = owners.acquire(source, "replacement", Duration.ofSeconds(30)).orElseThrow();
        assertFalse(polls.begin(next, "paused", Duration.ofSeconds(1)));
        assertFalse(polls.finish(lease, "attempt-" + winner, Duration.ofSeconds(1), new PollRepository.Completion(PollRepository.Outcome.SUCCESS, 0, 0)));
        new MongoPollingSchema(db).provision(Set.of(source));
        assertTrue(db.collection(MongoCatalogSchema.SOURCES).find().first().getBoolean("pollPaused"));
    }

    @Test @Order(3) void concurrentJobClaimsAndRecoveryFenceOldWorker() throws Exception {
        var queue = new MongoDeliveryQueue(db);
        var now = Instant.now().minusSeconds(2).truncatedTo(ChronoUnit.MILLIS);
        var ready = DeliveryJob.ready("job", new DeliveryKey("event", "200"), new DestinationRef("100", "200", "dest", "inc"), "sub", now);
        db.collection(DELIVERIES).insertOne(AlertDocuments.delivery(ready));
        var claims = race(16, i -> queue.claim("job", "owner-" + i, Duration.ofSeconds(30)));
        assertEquals(1, claims.stream().filter(Optional::isPresent).count());
        var claimed = claims.stream().flatMap(Optional::stream).findFirst().orElseThrow();
        // Deterministic database-time expiry without waiting for production lease durations.
        db.collection(DELIVERIES).updateOne(eq("_id", "job"), new Document("$set", new Document("leaseUntil", Date.from(now.plusMillis(1)))));
        var recovered = race(16, i -> queue.recoverExpired("job", claimed.revision()));
        assertEquals(1, recovered.stream().filter(Optional::isPresent).count());
        var replacement = queue.claim("job", "replacement", Duration.ofSeconds(30)).orElseThrow();
        assertEquals(claimed.generation() + 1, replacement.generation());
        assertTrue(queue.recoverExpired("job", claimed.revision()).isEmpty());
        assertEquals("replacement", AlertDocuments.delivery(db.collection(DELIVERIES).find().first()).lease().orElseThrow().token());
    }

    ConfigurationChange create(String action) {
        return new ConfigurationChange(action, "300", "100", 0, new ConfigurationChange.PutSubscription("200",
                new StoreId("epic"), new Market("EG"), Topic.FREE_GAME, true, Set.of("500")));
    }
    ConfigurationAccess.Proof proof(ConfigurationChange change, GuildConfiguration before) {
        // Evidence timestamp comes from this fixture's DB clock, as permission checks require a shared clock domain.
        Instant now = replica.client.getDatabase("admin").runCommand(new Document("hello", 1)).getDate("localTime").toInstant();
        return new ConfigurationAccess.Proof(ConfigurationAccess.requirements(change, policy.apply(before, change, now)), now, now.plusSeconds(60));
    }

    @Test @Order(4) void configurationEditsAtSameRevisionCannotBothCommit() throws Exception {
        var repo = new MongoConfigurationRepository(db); var create = create("400");
        assertEquals(ConfigurationRepository.Status.APPLIED, repo.commit(create, proof(create, GuildConfiguration.absent("100")), policy).status());
        var before = repo.load("100");
        var change = new ConfigurationChange("401", "300", "100", 1, new ConfigurationChange.SetGuildEnabled(false));
        var other = new ConfigurationChange("402", "300", "100", 1, new ConfigurationChange.RemoveDestination("200"));
        var firstProof = proof(change, before); var secondProof = proof(other, before);
        var outcomes = race(2, i -> repo.commit(i == 0 ? change : other, i == 0 ? firstProof : secondProof, policy));
        assertEquals(1, outcomes.stream().filter(r -> r.status() == ConfigurationRepository.Status.APPLIED).count());
        assertEquals(1, outcomes.stream().filter(r -> r.status() == ConfigurationRepository.Status.REVISION_CONFLICT).count());
        assertEquals(2, repo.load("100").revision());
        assertEquals(2, db.collection(MongoConfigurationSchema.AUDIT).countDocuments());
        assertEquals(2, AlertDocuments.number(db.collection(GUILDS).find().first(), "revision"));
    }

    @Test @Order(5) void transientTransactionAbortRetriesWithoutDuplicateAudit() {
        var repo = new MongoConfigurationRepository(db); var change = create("400");
        var evidence = proof(change, GuildConfiguration.absent("100"));
        replica.failOnce("insert", 112, "TransientTransactionError");
        assertEquals(ConfigurationRepository.Status.APPLIED, repo.commit(change, evidence, policy).status());
        assertEquals(1, db.collection(MongoConfigurationSchema.AUDIT).countDocuments());
        assertEquals(1, repo.load("100").revision());
        assertEquals(ConfigurationRepository.Status.REPLAYED, repo.commit(change, evidence, policy).status());
    }

    @Test @Order(6) void unknownCommitReplyRetriesCommitWithoutDuplicatingWrites() {
        var repo = new MongoConfigurationRepository(db); var change = create("400");
        var evidence = proof(change, GuildConfiguration.absent("100"));
        replica.failOnce("commitTransaction", 91, "UnknownTransactionCommitResult");
        assertEquals(ConfigurationRepository.Status.APPLIED, repo.commit(change, evidence, policy).status());
        assertEquals(1, db.collection(MongoConfigurationSchema.AUDIT).countDocuments());
        assertEquals(1, db.collection(SUBSCRIPTIONS).countDocuments());
    }

    record DeliveryFixture(MongoConfigurationRepository configs, MongoDeliveryRepository deliveries,
                           DeliveryRepository.AuthorizationRequest request, AutomaticSendEngine rule, Offer offer) {}
    DeliveryFixture deliveryFixture() {
        var configs = new MongoConfigurationRepository(db); var create = create("400");
        assertEquals(ConfigurationRepository.Status.APPLIED, configs.commit(create, proof(create, GuildConfiguration.absent("100")), policy).status());
        var destination = configs.load("100").destinations().getFirst(); var setting = destination.subscriptions().getFirst();
        Instant now = replica.client.getDatabase("admin").runCommand(new Document("hello", 1)).getDate("localTime").toInstant();
        var key = new OfferKey(new StoreId("epic"), "synthetic-campaign", new Market("EG"));
        var offer = new Offer(key, "synthetic-edition", Offer.Kind.FREE_TO_KEEP_BASE_GAME,
                new Offer.Evidence(Offer.Proof.VERIFIED, Offer.Proof.VERIFIED, Offer.Proof.VERIFIED, Offer.Proof.VERIFIED,
                        new SourceId("test"), "test-item", "test-rule"), Optional.of(new Offer.Money(0, Currency.getInstance("USD"))),
                Optional.of(now.minusSeconds(60)), Optional.of(now.plusSeconds(600)), now, 1);
        db.collection(MongoCatalogSchema.OFFERS).insertOne(CatalogDocuments.offer(offer));
        db.collection(MongoCatalogSchema.EVENTS).insertOne(CatalogDocuments.event(new CatalogDocuments.Event("event", offer, now)));
        var ready = DeliveryJob.ready("job", new DeliveryKey("event", "200"), destination.ref(), setting.id(), now);
        db.collection(DELIVERIES).insertOne(AlertDocuments.delivery(ready));
        db.collection(BACKLOG).updateOne(eq("_id", "delivery"), new Document("$set", new Document("pendingJobs", 1L)));
        var reviews = new MongoOwnerReviewRepository(db, "900", new FreeGamePolicy(Duration.ofMinutes(5), false));
        assertEquals(OwnerReviewRepository.Status.APPLIED, reviews.prepare("900", "500", 0, offer, MongoOwnerReviewTest.content(offer)).status());
        assertEquals(OwnerReviewRepository.Status.APPLIED, reviews.verify("900", "501", key, 1).status());
        assertEquals(OwnerReviewRepository.Status.APPLIED, reviews.release("900", "502", key, 2, 10).status());
        var deliveries = new MongoDeliveryRepository(db, "900");
        var claimed = deliveries.claim("job", "worker", Duration.ofMinutes(1)).orElseThrow();
        var runtime = new MongoOwnershipRepository(db).acquire(new OwnershipRepository.Runtime(), "runtime", Duration.ofMinutes(1)).orElseThrow();
        var payload = new AutomaticSendEngine.PreparedPayload(key, setting.id(), destination.ref(), "attempt", "nonce", 1, setting.revision(), org.bunnys.bunnynexus.alerts.adapters.discord.AlertMessageRenderer.TEMPLATE, "a".repeat(64), setting.roles(), MongoOwnerReviewTest.material(offer));
        var request = new DeliveryRepository.AuthorizationRequest("job", claimed.revision(), claimed.lease().orElseThrow(), payload,
                new DeliveryRepository.PermissionCheck(destination.ref(), setting.roles(), now.plusSeconds(60)), runtime);
        return new DeliveryFixture(configs, deliveries, request, new AutomaticSendEngine(new FreeGamePolicy(Duration.ofMinutes(5), false)), offer);
    }

    @Test @Order(7) void concurrentAuthorizationsAndReceiptsCommitExactlyOnce() throws Exception {
        var f = deliveryFixture();
        var attempts = race(8, i -> f.deliveries.authorize(f.request, f.rule));
        assertEquals(1, attempts.stream().filter(Optional::isPresent).count());
        var sending = ((AutomaticSendEngine.Sending) attempts.stream().flatMap(Optional::stream).findFirst().orElseThrow()).job();
        assertEquals(1, db.collection(ATTEMPTS).countDocuments());
        var receipts = race(8, i -> f.deliveries.recordOutcome(sending.id(), sending.revision(), sending.lease().orElseThrow(),
                sending.attempt().orElseThrow().id(), new DeliveryJob.Accepted("900"), Optional.empty()));
        assertEquals(1, receipts.stream().filter(Optional::isPresent).count());
        assertEquals(DeliveryJob.State.SENT, AlertDocuments.delivery(db.collection(DELIVERIES).find().first()).state());
        assertEquals("SENT", db.collection(ATTEMPTS).find().first().getString("outcome"));
        assertEquals(0, AlertDocuments.number(db.collection(BACKLOG).find(eq("_id", "delivery")).first(), "pendingJobs"));
    }

    @Test @Order(8) void abandonedSendingBecomesUncertainAndCannotBeBlindlyRetried() throws Exception {
        var f = deliveryFixture();
        var sending = ((AutomaticSendEngine.Sending) f.deliveries.authorize(f.request, f.rule).orElseThrow()).job();
        // Expire the durable lease after a hypothetical crash/remote acceptance with no recorded receipt.
        Instant expired = sending.createdAt().plusMillis(1);
        while (!Instant.now().isAfter(expired)) Thread.sleep(2);
        db.collection(DELIVERIES).updateOne(eq("_id", "job"), new Document("$set", new Document("leaseUntil", Date.from(expired))));
        var results = race(8, i -> f.deliveries.recoverExpired("job", sending.revision()));
        assertEquals(1, results.stream().filter(Optional::isPresent).count());
        assertEquals(DeliveryJob.State.UNCERTAIN, AlertDocuments.delivery(db.collection(DELIVERIES).find().first()).state());
        assertEquals("UNCERTAIN", db.collection(ATTEMPTS).find().first().getString("outcome"));
        assertEquals(1, AlertDocuments.number(db.collection(BACKLOG).find(eq("_id", "delivery")).first(), "pendingJobs"));
        assertTrue(f.deliveries.claim("job", "unsafe-retry", Duration.ofSeconds(30)).isEmpty());
        assertTrue(f.deliveries.recordOutcome("job", sending.revision(), sending.lease().orElseThrow(), "attempt",
                new DeliveryJob.Accepted("900"), Optional.empty()).isEmpty());
    }

    @Test @Order(9) void disableAndAuthorizationRaceSerializesWithoutPartialAttempt() throws Exception {
        var f = deliveryFixture();
        var change = new ConfigurationChange("401", "300", "100", 1, new ConfigurationChange.SetGuildEnabled(false));
        var evidence = proof(change, f.configs.load("100"));
        var results = race(2, i -> i == 0 ? f.deliveries.authorize(f.request, f.rule)
                : f.configs.commit(change, evidence, policy));
        assertEquals(ConfigurationRepository.Status.APPLIED, ((ConfigurationRepository.Result) results.get(1)).status());
        assertFalse(f.configs.load("100").enabled());
        var job = AlertDocuments.delivery(db.collection(DELIVERIES).find().first());
        assertTrue(job.state() == DeliveryJob.State.SENDING || job.state() == DeliveryJob.State.SKIPPED);
        assertEquals(job.state() == DeliveryJob.State.SENDING ? 1 : 0, db.collection(ATTEMPTS).countDocuments());
        // A send committed first may proceed; after disable, a fresh request may not be authorized.
        var second = DeliveryJob.ready("job-after-disable", new DeliveryKey("event-after-disable", "200"), job.destination(), job.subscriptionId(),
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
        db.collection(DELIVERIES).insertOne(AlertDocuments.delivery(second));
        var laterOffer = new Offer(new OfferKey(f.offer.key().store(), "later-synthetic-campaign", f.offer.key().market()),
                f.offer.editionId(), f.offer.kind(), f.offer.evidence(), f.offer.price(), f.offer.startsAt(), f.offer.endsAt(), f.offer.verifiedAt(), 1);
        db.collection(MongoCatalogSchema.OFFERS).insertOne(CatalogDocuments.offer(laterOffer));
        db.collection(MongoCatalogSchema.EVENTS).insertOne(CatalogDocuments.event(new CatalogDocuments.Event("event-after-disable", laterOffer, laterOffer.verifiedAt())));
        db.collection(BACKLOG).updateOne(eq("_id", "delivery"), new Document("$inc", new Document("pendingJobs", 1L)));
        var claimed = f.deliveries.claim(second.id(), "next", Duration.ofSeconds(30)).orElseThrow();
        var request = new DeliveryRepository.AuthorizationRequest(second.id(), claimed.revision(), claimed.lease().orElseThrow(),
                f.request.payload(), f.request.permissionCheck(), f.request.runtimeLease());
        assertInstanceOf(AutomaticSendEngine.Skipped.class, f.deliveries.authorize(request, f.rule).orElseThrow());
    }

    @Test @Order(10) void concurrentFanoutPageReplayDoesNotDuplicateChannelJobOrBacklog() throws Exception {
        var f = deliveryFixture();
        var plan = FanoutPlan.automatic("plan", "event", new FreeGameKey(f.offer.key()), f.offer.verifiedAt());
        db.collection(PLANS).insertOne(AlertDocuments.plan(plan));
        var fanout = new MongoFanoutRepository(db);
        var claimed = fanout.claim("plan", "fanout-worker", Duration.ofSeconds(30)).orElseThrow();
        var results = race(8, i -> fanout.processPage("plan", claimed.revision(), claimed.lease().orElseThrow(), 64));
        assertEquals(1, results.stream().filter(r -> r == FanoutRepository.Result.PAGE_COMMITTED).count());
        assertEquals(7, results.stream().filter(r -> r == FanoutRepository.Result.CONFLICT).count());
        assertEquals(1, db.collection(DELIVERIES).countDocuments());
        assertEquals(1, AlertDocuments.number(db.collection(BACKLOG).find(eq("_id", "delivery")).first(), "pendingJobs"));
        var after = AlertDocuments.plan(db.collection(PLANS).find().first());
        assertEquals(1, after.duplicates());
        var nextPage = fanout.claim("plan", "next-page-worker", Duration.ofSeconds(30)).orElseThrow();
        assertEquals(FanoutRepository.Result.COMPLETE, fanout.processPage("plan", nextPage.revision(), nextPage.lease().orElseThrow(), 64));
    }

    @Test @Order(11) void duePageUsesIndexAtTwentyThousandJobs() {
        var rows = new ArrayList<Document>(); Instant now = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MILLIS);
        for (int i = 0; i < 20_000; i++) {
            String channel = Integer.toString(200_000 + i), guild = Integer.toString(100_000 + i % 1000);
            var job = DeliveryJob.ready(String.format(Locale.ROOT, "load-%05d", i), new DeliveryKey("load-event", channel),
                    new DestinationRef(guild, channel, "dest-" + i, "inc"), "sub-" + i, now);
            if (i % 3 == 0) job = job.claim("worker", now, now.plusSeconds(30));
            rows.add(AlertDocuments.delivery(job));
            if (rows.size() == 500) { db.collection(DELIVERIES).insertMany(rows); rows.clear(); }
        }
        var page = new MongoSchedulingRepository(db).due(DeliveryJob.State.READY, Optional.empty(), Optional.empty(), 25);
        assertEquals(25, page.size());
        var filter = new Document("schemaVersion", 1).append("state", "READY")
                .append("$expr", new Document("$lte", List.of("$dueAt", "$$NOW")));
        var explain = db.collection(DELIVERIES).find(filter).sort(new Document("dueAt", 1).append("_id", 1))
                .collation(SIMPLE).limit(25).explain(com.mongodb.ExplainVerbosity.EXECUTION_STATS);
        var stats = explain.get("executionStats", Document.class);
        assertTrue(explain.get("queryPlanner", Document.class).get("winningPlan", Document.class).toJson().contains("IXSCAN"));
        assertEquals(25, ((Number) stats.get("nReturned")).intValue());
        assertTrue(((Number) stats.get("totalDocsExamined")).longValue() <= 50);
        System.out.println("MONGO_DUE_PAGE jobs=20000 returned=25 docsExamined=" + stats.get("totalDocsExamined")
                + " keysExamined=" + stats.get("totalKeysExamined") + " serverMillis=" + stats.get("executionTimeMillis"));
    }

    @Test @Order(20) void ownerReviewPersistsAcrossRepositoriesAndNeedsSeparateVerifyAndRelease() {
        var f = deliveryFixture();
        var policy = new FreeGamePolicy(Duration.ofMinutes(5), false);
        var owner = new MongoOwnerReviewRepository(db, "900", policy);
        var key = f.offer.key();
        assertEquals(OwnerReviewRepository.Status.REPLAYED, owner.release("900", "502", key, 2, 10).status());
        assertEquals(OwnerReviewRepository.Status.CONFLICT, owner.release("900", "502", key, 2, 11).status());
        assertEquals(3, owner.get("900", key).orElseThrow().revision());
        assertThrows(SecurityException.class, () -> owner.revoke("901", "503", key, 3));
        assertEquals(OwnerReviewRepository.Status.APPLIED, owner.prepare("900", "503", 3, f.offer, MongoOwnerReviewTest.content(f.offer)).status());
        assertEquals(OwnerReviewRepository.Status.CONFLICT, owner.release("900", "504", key, 4, 1).status());
        assertEquals(OwnerReviewRepository.Status.APPLIED, owner.verify("900", "505", key, 4).status());
        var blocked = new AutomaticSendEngine.Blocked(AutomaticSendEngine.BlockReason.OWNER_RELEASE_REQUIRED);
        assertEquals(blocked, f.deliveries.authorize(f.request, f.rule).orElseThrow());
        var restarted = new MongoOwnerReviewRepository(db, "900", policy);
        assertEquals(OwnerReviewRepository.State.VERIFIED, restarted.get("900", key).orElseThrow().state());
        assertEquals(OwnerReviewRepository.Status.APPLIED, restarted.release("900", "506", key, 5, 1).status());
        assertInstanceOf(AutomaticSendEngine.Sending.class, f.deliveries.authorize(f.request, f.rule).orElseThrow());
        assertEquals(1, restarted.get("900", key).orElseThrow().usedAttempts());
    }
    @Test @Order(21) void ownerReleaseCounterIsAtomicAndRollsBackWithTransaction() throws Exception {
        var f = deliveryFixture();
        String id = CatalogDocuments.offerId(f.offer.key());
        db.collection(MongoOwnerReviewSchema.REVIEWS).updateOne(eq("_id", id), new Document("$set", new Document("maximumAttempts", 1)));
        assertThrows(IllegalStateException.class, () -> db.transaction(session -> {
            var release = db.collection(MongoOwnerReviewSchema.REVIEWS).find(session, eq("_id", id)).first();
            assertTrue(MongoOwnerReviewRepository.reserve(session, db, release));
            throw new IllegalStateException("abort after reservation");
        }));
        assertEquals(0, AlertDocuments.number(db.collection(MongoOwnerReviewSchema.REVIEWS).find(eq("_id", id)).first(), "usedAttempts"));
        var results = race(8, i -> db.transaction(session -> {
            var release = db.collection(MongoOwnerReviewSchema.REVIEWS).find(session, eq("_id", id)).first();
            return MongoOwnerReviewRepository.reserve(session, db, release);
        }));
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(new AutomaticSendEngine.Blocked(AutomaticSendEngine.BlockReason.OWNER_RELEASE_REQUIRED),
                f.deliveries.authorize(f.request, f.rule).orElseThrow());
        assertEquals(0, db.collection(ATTEMPTS).countDocuments());
    }
    @Test @Order(22) void ownerRevocationAndNewPreviewBlockSendsWithoutResetOnMigration() {
        var f = deliveryFixture();
        var owner = new MongoOwnerReviewRepository(db, "900", new FreeGamePolicy(Duration.ofMinutes(5), false));
        assertEquals(OwnerReviewRepository.Status.APPLIED, owner.revoke("900", "503", f.offer.key(), 3).status());
        new MongoOwnerReviewSchema(db).install(); new MongoOwnerReviewSchema(db).verifyInstalled();
        assertEquals(OwnerReviewRepository.State.REVOKED, owner.get("900", f.offer.key()).orElseThrow().state());
        assertEquals(new AutomaticSendEngine.Blocked(AutomaticSendEngine.BlockReason.OWNER_RELEASE_REQUIRED),
                f.deliveries.authorize(f.request, f.rule).orElseThrow());
        assertEquals(0, db.collection(ATTEMPTS).countDocuments());
        assertEquals(4, db.collection(MongoOwnerReviewSchema.AUDIT).countDocuments());
        db.collection(MongoOwnerReviewSchema.REVIEWS).dropIndex("owner_review_state");
        assertThrows(IllegalStateException.class, () -> new MongoOwnerReviewSchema(db).verifyInstalled());
    }

    @Test @Order(23) void ownerRevocationSerializesAgainstSendAuthorization() throws Exception {
        var f = deliveryFixture();
        var owner = new MongoOwnerReviewRepository(db, "900", new FreeGamePolicy(Duration.ofMinutes(5), false));
        var results = race(2, i -> i == 0 ? owner.revoke("900", "503", f.offer.key(), 3)
                : f.deliveries.authorize(f.request, f.rule));
        assertEquals(OwnerReviewRepository.Status.APPLIED, ((OwnerReviewRepository.Result) results.getFirst()).status());
        var review = owner.get("900", f.offer.key()).orElseThrow();
        assertEquals(OwnerReviewRepository.State.REVOKED, review.state());
        long attempts = db.collection(ATTEMPTS).countDocuments();
        assertTrue(attempts <= 1); assertEquals(attempts, review.usedAttempts());
        var later = f.deliveries.authorize(f.request, f.rule);
        assertTrue(later.isEmpty() || later.orElseThrow() instanceof AutomaticSendEngine.Blocked);
    }

    @Test @Order(99) void majorityCommittedConfigurationSurvivesPrimaryProcessCrash() throws Exception {
        var repo = new MongoConfigurationRepository(db); var change = create("400");
        assertEquals(ConfigurationRepository.Status.APPLIED, repo.commit(change, proof(change, GuildConfiguration.absent("100")), policy).status());
        var primary = replica.processes.get(replica.primary());
        primary.destroyForcibly(); assertTrue(primary.waitFor(10, TimeUnit.SECONDS));
        assertEquals(1, repo.load("100").revision());
        assertEquals(1, db.collection(MongoConfigurationSchema.AUDIT).countDocuments());
        var disable = new ConfigurationChange("401", "300", "100", 1, new ConfigurationChange.SetGuildEnabled(false));
        assertEquals(ConfigurationRepository.Status.APPLIED, repo.commit(disable, proof(disable, repo.load("100")), policy).status());
        assertFalse(repo.load("100").enabled());
    }

    static <T> List<T> race(int workers, IntFunction<T> operation) throws Exception {
        var start = new CountDownLatch(1); var ready = new CountDownLatch(workers);
        try (var pool = Executors.newFixedThreadPool(workers)) {
            var futures = new ArrayList<Future<T>>();
            for (int i = 0; i < workers; i++) { int index = i; futures.add(pool.submit(() -> { ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Race start timeout"); return operation.apply(index); })); }
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            var results = new ArrayList<T>(); for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS)); return results;
        } finally { start.countDown(); }
    }
}
