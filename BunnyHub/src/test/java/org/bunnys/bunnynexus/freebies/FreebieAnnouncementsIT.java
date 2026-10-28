package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real MongoDB: cross-source identity, delayed enrichment, announcement pages and publication jobs. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreebieAnnouncementsIT {
    static final Instant START = FreebieIdentityTest.START, END = FreebieIdentityTest.END;
    static final String CHANNEL = "200000000000000000", GUILD = "100000000000000000";
    static final URI RESOLVED = URI.create("https://store.epicgames.com/p/home-19212a?lang=en-US");
    final Instant now = START.plus(Duration.ofMinutes(5));
    LocalReplicaSet replica;
    MongoDatabase db;
    FreebieRepository repository;
    MongoCollection<Document> offers;

    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() {
        db = replica.client.getDatabase("announce_it_" + UUID.randomUUID().toString().replace("-", ""));
        repository = new FreebieRepository(db); repository.installIndexes(); offers = db.getCollection(FreebieRepository.OFFERS);
    }

    FreebieFeed.Candidate epic() { return FreebieIdentityTest.epic(START, END); }
    FreebieFeed.Candidate gp(String id, String title, String platforms, boolean resolved) {
        return FreebieIdentityTest.gamerpower(id, title, platforms, resolved ? Optional.of(RESOLVED) : Optional.empty());
    }
    long pending() { return offers.countDocuments(eq("state", "PENDING")); }

    // ------------------------------------------------------------------ identity, both arrival orders

    @Test void epicFirstThenAVerifiedGamerPowerReportFortyMinutesLaterIsOneGiveaway() {
        assertTrue(repository.recordSeen(epic(), now));
        assertFalse(repository.recordSeen(gp("3823", "Home (Epic Games) Giveaway", "PC, Android, iOS, Epic Games Store", true), now.plus(Duration.ofMinutes(40))));
        assertEquals(1, offers.countDocuments());
        assertEquals(1, pending(), "the second report never opens a review");
        var row = offers.find().first();
        assertEquals("Home - A Unique Horror Adventure", row.getString("title"), "the official title is kept");
        assertEquals("$4.99", row.getString("worth"), "missing worth is enriched from GamerPower");
        assertEquals(Date.from(END), row.getDate("endsAt"), "Epic's exact end is never replaced by GamerPower's estimate");
        assertEquals(Set.of("epic", "gamerpower"), row.get("evidence", Document.class).keySet());
        assertEquals("PC, Android, iOS, Epic Games Store", row.get("reported", Document.class).get("gamerpower", Document.class).getString("platforms"));
        assertNull(row.get("sourceCandidates"), "a verified storefront identity needs no owner question");
    }

    @Test void gamerPowerFirstThenEpicAdoptsOfficialFieldsWhileStillInReview() {
        assertTrue(repository.recordSeen(gp("3823", "Home (Epic Games) Giveaway", "PC, Android, iOS, Epic Games Store", true), now));
        assertFalse(repository.recordSeen(epic(), now.plus(Duration.ofMinutes(40))));
        assertEquals(1, offers.countDocuments());
        var row = offers.find().first();
        assertEquals("gamerpower:3823", row.getString("_id"), "the canonical ID and its references are preserved");
        assertEquals("Home - A Unique Horror Adventure", row.getString("title"));
        assertEquals(Date.from(END), row.getDate("endsAt"));
        assertTrue(row.getBoolean("reviewRefresh"));
        assertNotNull(row.getString("groupKey"));
        assertEquals("c7e46cce751b40b6937e878abd0fc531", row.get("epicOffer", Document.class).getString("namespace"));
    }

    @Test void anUnresolvedGamerPowerReportWithARelatedTitleOnlyBecomesAnOwnerQuestion() {
        repository.recordSeen(epic(), now);
        assertFalse(repository.recordSeen(gp("3823", "Home (Epic Games) Giveaway", "PC, Android, iOS, Epic Games Store", false), now));
        assertEquals(1, offers.countDocuments());
        assertEquals(1, pending());
        assertEquals(1, offers.find().first().get("sourceCandidates", Document.class).size());
        assertFalse(offers.find().first().get("evidence", Document.class).containsKey("gamerpower"), "no liveness authority before a decision");
    }

    @Test void delayedEnrichmentAfterApprovalKeepsApprovedTextAndOnlyShortensTheWindow() {
        repository.recordSeen(gp("3823", "Home (Epic Games) Giveaway", "PC, Epic Games Store", true), now);
        repository.transition("gamerpower:3823", FreebieOffer.State.PENDING, FreebieOffer.State.APPROVED, "owner", now);
        repository.recordSeen(epic(), now.plus(Duration.ofHours(2)));
        var row = offers.find().first();
        assertEquals("Home (Epic Games) Giveaway", row.getString("title"));
        assertTrue(row.getBoolean("officialTextDiffers"));
        assertEquals(Date.from(END), row.getDate("endsAt"));
        assertEquals(1L, ((Number) row.get("cardRevision")).longValue());
        assertEquals("APPROVED", row.getString("state"));
        assertEquals("owner", row.getString("decidedBy"), "approval is never changed by a source");
    }

    @Test void repeatGiveawaysEditionsAndOtherPlatformsStaySeparate() {
        repository.recordSeen(epic(), now);
        var repeat = FreebieIdentityTest.epic(END.plus(Duration.ofDays(60)), END.plus(Duration.ofDays(67)));
        assertTrue(repository.recordSeen(repeat, END.plus(Duration.ofDays(60))), "a later window of the same product is a new occurrence");
        assertFalse(repository.recordSeen(gp("4000", "Home Deluxe Edition (Epic Games) Giveaway", "PC, Epic Games Store", true), now));
        assertEquals(1, offers.find(eq("_id", FreebieSourceStore.canonicalId(epic()))).first().get("sourceCandidates", Document.class).size(),
                "an edition difference waits for the owner");
        assertTrue(repository.recordSeen(gp("4001", "Home (Android) Giveaway", "Android", true), now),
                "a mobile-only report is a different entitlement and never joins the PC giveaway");
        assertEquals(3, offers.countDocuments());
    }

    @Test void concurrentReportsInEitherOrderConvergeOnOneGiveaway() throws Exception {
        var e = epic(); var g = gp("3823", "Home (Epic Games) Giveaway", "PC, Epic Games Store", true);
        try (var pool = Executors.newFixedThreadPool(12)) {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 30; i++) { var c = i % 2 == 0 ? g : e; tasks.add(() -> repository.recordSeen(c, now)); }
            int created = 0;
            for (var f : pool.invokeAll(tasks)) if (f.get()) created++;
            assertEquals(1, created);
        }
        assertEquals(1, offers.countDocuments(), "two providers arriving at once still open one review");
        assertEquals(2, offers.find().first().getList("sourceAliases", String.class).size());
        assertNull(offers.find().first().get("sourceCandidates"));
    }

    // ------------------------------------------------------------------ EGData enrichment

    @Test void regionsAttachToTheExactOfferAndMobileOffersOnlyWhileInReview() throws Exception {
        repository.recordSeen(epic(), now);
        var enrichment = new FreebieEnrichment(offers, null, Clock.fixed(EgDataIntakeTest.NOW, ZoneOffset.UTC));
        var list = new EgDataIntake().decode(EgDataIntakeTest.captured(), EgDataIntakeTest.NOW).offers();
        assertEquals(3, enrichment.apply(list, EgDataIntakeTest.NOW), "PC regions plus Android and iOS offers");
        var facts = repository.facts(FreebieSourceStore.canonicalId(epic()));
        assertEquals(List.of("SA", "TW"), FreebieRegions.blocked(facts.regions(), EgDataIntakeTest.NOW));
        assertEquals(2, facts.variants().size());
        assertTrue(facts.variants().stream().allMatch(FreebieFacts.Variant::reviewed));
        assertEquals(0, enrichment.apply(list, EgDataIntakeTest.NOW), "unchanged evidence is not a change");
    }

    @Test void variantsFoundAfterApprovalAreNotPublishedAndFailuresLeaveRegionsUnknown() throws Exception {
        repository.recordSeen(epic(), now);
        String id = FreebieSourceStore.canonicalId(epic());
        repository.transition(id, FreebieOffer.State.PENDING, FreebieOffer.State.APPROVED, "owner", now);
        assertTrue(repository.facts(id).regions().isEmpty());
        var enrichment = new FreebieEnrichment(offers, null, Clock.fixed(EgDataIntakeTest.NOW, ZoneOffset.UTC));
        enrichment.apply(new EgDataIntake().decode(EgDataIntakeTest.captured(), EgDataIntakeTest.NOW).offers(), EgDataIntakeTest.NOW);
        var facts = repository.facts(id);
        assertTrue(facts.publicVariants(EgDataIntakeTest.NOW).isEmpty(), "new claimable variants need approval");
        assertEquals(1L, ((Number) offers.find(eq("_id", id)).first().get("cardRevision")).longValue(), "a new restriction refreshes cards");
        var repeatDb = offers.find(eq("_id", id)).first();
        assertEquals("APPROVED", repeatDb.getString("state"));
    }

    // ------------------------------------------------------------------ routes and pages

    @Test void routesAreReservedOnceAndOffersWithHistoricalRowsStayLegacy() {
        repository.recordSeen(epic(), now);
        String id = FreebieSourceStore.canonicalId(epic());
        assertEquals(Optional.of("offer:" + id), repository.routes().route(id, true, Optional.empty()),
                "a group key added by enrichment never chooses the route; only a release snapshot does");
        assertEquals(repository.routes().route(id, true, Optional.of("epic|m|1")), repository.routes().route(id, false, Optional.empty()),
                "the first decision is permanent");
        repository.recordSeen(gp("1", "Old", "PC, Steam", false), now);
        db.getCollection(FreebieRepository.DELIVERIES).insertOne(new Document("_id", "gamerpower:1|" + CHANNEL).append("offerId", "gamerpower:1"));
        assertTrue(repository.routes().route("gamerpower:1", true, Optional.of("epic|m|1")).isEmpty(), "existing deliveries are never reopened as a group");
        assertEquals("legacy", offers.find(eq("_id", "gamerpower:1")).first().getString("route"));
    }

    @Test void onlyOneWorkerCreatesAPageAndLateMembersJoinOnlyWithRoom() throws Exception {
        var store = repository.announcements();
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                int n = i;
                tasks.add(() -> store.create("k", GUILD, CHANNEL, 1, Optional.empty(), List.of("o" + n), List.of("o" + n + "|c"), "t" + n,
                        Duration.ofMinutes(3), now).isPresent());
            }
            int created = 0;
            for (var f : pool.invokeAll(tasks)) if (f.get()) created++;
            assertEquals(1, created);
        }
        var page = store.pages("k", CHANNEL).getFirst();
        String token = page.getString("token");
        assertFalse(store.join(page.getString("_id"), "late", "late|c", now), "nobody joins a page that may be in flight");
        assertTrue(store.submitting(page.getString("_id"), token, now));
        assertTrue(store.sent(page.getString("_id"), token, "m1", false, now));
        for (int i = 0; i < 6; i++) store.join(page.getString("_id"), "late" + i, "late" + i + "|c", now);
        assertEquals(FreebieAnnouncementStore.PAGE_SIZE, store.get(page.getString("_id")).orElseThrow().getList("members", String.class).size());
        assertNull(store.get(page.getString("_id")).orElseThrow().get("publishPending"), "a non-allowlisted channel is never published");
    }

    @Test void concurrentChangesAreAppliedBySerializedRefreshesUpToTheLatestRevision() {
        var store = repository.announcements();
        var page = store.create("k", GUILD, CHANNEL, 1, Optional.empty(), List.of("a", "b"), List.of("a|c", "b|c"), "t", Duration.ofMinutes(3), now).orElseThrow();
        store.submitting(page.getString("_id"), "t", now);
        store.touch("a", now); // ended while the post was in flight
        store.sent(page.getString("_id"), "t", "m1", true, now);
        var first = store.claimRefresh(now, Duration.ofMinutes(3)).orElseThrow();
        assertTrue(store.claimRefresh(now, Duration.ofMinutes(3)).isEmpty(), "one refresh per page at a time");
        store.touch("b", now); // a different game ends during that edit
        assertTrue(store.refreshed(first, "edited", now));
        var second = store.claimRefresh(now, Duration.ofMinutes(3)).orElseThrow();
        assertEquals(3L, second.revision(), "the second change is not lost");
        store.refreshed(second, "edited", now);
        assertTrue(store.claimRefresh(now, Duration.ofMinutes(3)).isEmpty());
        assertTrue(store.settled(store.get(page.getString("_id")).orElseThrow()));
    }

    @Test void anOwnerRetryReopensAFailedPageOnceWithAFreshBudgetAndHistoryCheck() {
        var store = repository.announcements();
        var page = store.create("k", GUILD, CHANNEL, 1, Optional.empty(), List.of("a"), List.of("a|c"), "t", Duration.ofMinutes(3), now).orElseThrow();
        store.submitting(page.getString("_id"), "t", now);
        assertTrue(store.failed(page.getString("_id"), "t", "MISSING_PERMISSIONS", now));
        var failed = store.get(page.getString("_id")).orElseThrow();
        var first = store.reopen(failed, "r1", Duration.ofMinutes(3), now.plusSeconds(60));
        assertTrue(first.isPresent());
        assertTrue(store.reopen(failed, "r2", Duration.ofMinutes(3), now.plusSeconds(61)).isEmpty(), "concurrent retries reopen it once");
        assertTrue(first.get().getBoolean("verifyFirst"));
        assertEquals(0, first.get().getInteger("submissions"));
    }

    // ------------------------------------------------------------------ publication jobs

    Document sentPage(String key, int number, String messageId, boolean publish) {
        var store = repository.announcements();
        var page = store.create(key, GUILD, CHANNEL, number, Optional.empty(), List.of("a" + number), List.of("a" + number + "|c"), "t",
                Duration.ofMinutes(3), now).orElseThrow();
        store.sent(page.getString("_id"), "t", messageId, publish, now);
        return store.get(page.getString("_id")).orElseThrow();
    }

    @Test void schedulingIsIdempotentAndOldPagesAreNeverPublished() {
        var jobs = repository.publications();
        var page = sentPage("k", 1, "m1", true);
        sentPage("old", 1, "m0", false);
        assertEquals(1, repository.announcements().publishPending(10).size(), "only pages sent while enabled are marked");
        assertTrue(jobs.plan(page, now));
        assertFalse(jobs.plan(page, now), "a duplicate plan (crash before clearing the marker) is ignored");
        repository.announcements().publishPlanned(page.getString("_id"));
        assertTrue(repository.announcements().publishPending(10).isEmpty());
        assertEquals(Map.of("QUEUED", 1L), jobs.counts());
    }

    @Test void aCrashDuringPublishingIsRecoveredAsUncertainAndTheBudgetSurvivesRestart() {
        var jobs = repository.publications();
        for (int i = 0; i < FreebiePublicationStore.HOURLY_LIMIT; i++) jobs.plan(sentPage("k" + i, 1, "m" + i, true), now);
        for (int i = 0; i < FreebiePublicationStore.HOURLY_LIMIT - 1; i++) {
            var job = jobs.claim(now, Duration.ofMinutes(3)).orElseThrow();
            assertTrue(jobs.published(job, false, now));
        }
        var crashed = jobs.claim(now, Duration.ofMinutes(3)).orElseThrow();
        jobs.submitting(crashed, now);
        var restarted = new FreebieRepository(db).publications();
        assertEquals(1, restarted.recover(now.plus(Duration.ofMinutes(4))));
        var retry = restarted.claim(now.plus(Duration.ofMinutes(4)), Duration.ofMinutes(3)).orElseThrow();
        assertTrue(retry.uncertain(), "the next attempt reads the CROSSPOSTED flag before anything else");
        assertEquals(FreebiePublicationStore.HOURLY_LIMIT, restarted.usedLastHour(CHANNEL, now.plus(Duration.ofMinutes(4))),
                "nine published plus this possibly-landed request, counted from storage after restart");
        assertTrue(restarted.delay(retry, "hourly publish limit", restarted.budgetFreesAt(CHANNEL, now), now.plus(Duration.ofMinutes(4))));
        var delayed = db.getCollection(FreebiePublicationStore.COLLECTION).find(eq("_id", retry.id())).first();
        assertEquals("DELAYED", delayed.getString("state"));
        assertEquals(Date.from(now.plus(Duration.ofHours(1)).plusSeconds(5)), delayed.getDate("nextAttemptAt"));
        assertTrue(restarted.claim(now.plus(Duration.ofMinutes(30)), Duration.ofMinutes(3)).isEmpty(), "a persisted delay holds across restarts");
    }

    @Test void concurrentWorkersNeverHoldTheSameJobAndLateOutcomesAreFenced() throws Exception {
        var jobs = repository.publications();
        jobs.plan(sentPage("k", 1, "m1", true), now);
        List<FreebiePublicationStore.Job> claimed = Collections.synchronizedList(new ArrayList<>());
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Void>> workers = new ArrayList<>();
            for (int i = 0; i < 8; i++) workers.add(() -> { jobs.claim(now, Duration.ofMinutes(3)).ifPresent(claimed::add); return null; });
            for (var f : pool.invokeAll(workers)) f.get();
        }
        assertEquals(1, claimed.size(), "only one worker may submit for a message");
        var first = claimed.getFirst();
        assertTrue(jobs.submitting(first, now));
        // The first worker's request is slow; its lease expires and another process recovers the job.
        Instant later = now.plus(Duration.ofMinutes(4));
        assertEquals(1, jobs.recover(later));
        var second = jobs.claim(later, Duration.ofMinutes(3)).orElseThrow();
        assertTrue(second.uncertain(), "the second attempt must read CROSSPOSTED first");
        assertFalse(jobs.published(first, false, later), "a late result from the lost lease changes nothing");
        assertTrue(jobs.published(second, true, later));
        assertTrue(jobs.claim(later.plus(Duration.ofHours(2)), Duration.ofMinutes(3)).isEmpty(), "published jobs are final");
    }

    @Test void aCrashBeforeOrAfterTheRequestIsRecoveredAsUncertainOnTheSameMessage() {
        var jobs = repository.publications();
        jobs.plan(sentPage("k", 1, "m1", true), now);
        var crashedBefore = jobs.claim(now, Duration.ofMinutes(3)).orElseThrow(); // died before persisting submission
        var restarted = new FreebieRepository(db).publications();
        restarted.recover(now.plus(Duration.ofMinutes(4)));
        var retry = restarted.claim(now.plus(Duration.ofMinutes(4)), Duration.ofMinutes(3)).orElseThrow();
        assertEquals(crashedBefore.messageId(), retry.messageId(), "recovery uses the same source message");
        assertEquals(crashedBefore.id(), retry.id());
        assertTrue(retry.uncertain(), "recovery is conservative even when submission may not have happened");
        assertEquals(1, db.getCollection(FreebiePublicationStore.COLLECTION).countDocuments(), "no second job for the message");
    }

    @Test void continuationPagesWaitForEarlierPagesAndDisablingCancelsPendingJobs() {
        var jobs = repository.publications();
        jobs.plan(sentPage("k", 1, "m1", true), now);
        jobs.plan(sentPage("k", 2, "m2", true), now);
        var first = jobs.claim(now, Duration.ofMinutes(3)).orElseThrow();
        assertEquals(1, first.page(), "pages are claimed in order");
        var second = jobs.claim(now, Duration.ofMinutes(3)).orElseThrow();
        assertTrue(jobs.earlierPageOpen(second));
        jobs.published(first, false, now);
        assertFalse(jobs.earlierPageOpen(second));
        jobs.delay(second, "x", now, now);
        assertEquals(1, jobs.cancelOpen(Optional.empty(), now));
        assertEquals(Map.of("CANCELLED", 1L, "PUBLISHED", 1L), jobs.counts());
    }

    @Test void retentionKeepsOffersWhosePagesStillHaveOpenWork() {
        repository.recordSeen(epic(), now);
        String id = FreebieSourceStore.canonicalId(epic());
        offers.updateOne(eq("_id", id), combine(set("state", "COMPLETED"), set("updatedAt", Date.from(now))));
        var store = repository.announcements();
        var page = store.create("k", GUILD, CHANNEL, 1, Optional.empty(), List.of(id), List.of(id + "|" + CHANNEL), "t", Duration.ofMinutes(3), now).orElseThrow();
        store.sent(page.getString("_id"), "t", "m", true, now);
        assertTrue(store.openWorkFor(id), "a page awaiting publication keeps its offer");
        store.publishPlanned(page.getString("_id"));
        assertFalse(store.openWorkFor(id));
        store.touch(id, now);
        assertTrue(store.openWorkFor(id), "an unapplied edit keeps its offer");
    }
}
