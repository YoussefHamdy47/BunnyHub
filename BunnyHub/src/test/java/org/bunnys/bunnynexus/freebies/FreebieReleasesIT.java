package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real MongoDB: release approval snapshots bound to reviewed membership and revisions, with crash recovery. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreebieReleasesIT {
    static final Instant NOW = FreebieHomeCaseIT.CAPTURED;
    LocalReplicaSet replica;
    MongoDatabase db;
    FreebieRepository repository;
    MongoCollection<Document> offers;
    final List<String> planned = Collections.synchronizedList(new ArrayList<>());
    FreebieApprovals approvals;
    String home, sker, key;

    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() throws Exception {
        db = replica.client.getDatabase("release_it_" + UUID.randomUUID().toString().replace("-", ""));
        repository = new FreebieRepository(db); repository.installIndexes(); offers = db.getCollection(FreebieRepository.OFFERS);
        var subscriptions = mock(FreebieSubscriptions.class);
        org.mockito.Mockito.when(subscriptions.audience(org.mockito.ArgumentMatchers.any())).thenReturn(new FreebieSubscriptions.Audience(1, 1));
        planned.clear();
        approvals = new FreebieApprovals(repository, subscriptions, mock(FreebieReviewChannel.class), FreebieFeatures.DEFAULT,
                offer -> planned.add(offer.id()), Clock.fixed(NOW, ZoneOffset.UTC));
        var h = FreebieHomeCaseIT.epic("Home - A Unique Horror Adventure");
        var s = FreebieHomeCaseIT.epic("Sker Ritual");
        repository.recordSeen(h, NOW); repository.recordSeen(s, NOW);
        home = FreebieSourceStore.canonicalId(h); sker = FreebieSourceStore.canonicalId(s);
        key = offers.find(eq("_id", home)).first().getString("groupKey");
        assertEquals(key, offers.find(eq("_id", sker)).first().getString("groupKey"), "same verified release start");
    }

    FreebieOffer offer(String id) { return repository.offer(id).orElseThrow(); }
    String preview(String actor, boolean whole) {
        return repository.releases().preview(key, approvals.members(offer(home), whole, NOW), actor, NOW).orElseThrow();
    }
    FreebieOffer.State state(String id) { return offer(id).state(); }

    @Test void aConfirmedReleaseCommitsOneSnapshotAndPlansBothUnderTheReleaseKey() {
        String token = preview("owner", true);
        String result = approvals.confirmRelease("owner", home, true, token);
        assertTrue(result.startsWith("Approved 2 game(s)"), result);
        assertEquals(FreebieOffer.State.APPROVED, state(home));
        assertEquals(FreebieOffer.State.APPROVED, state(sker));
        assertEquals(Optional.of(key), repository.routes().announcementKey(home));
        assertEquals(Optional.of(key), repository.routes().announcementKey(sker));
        assertTrue(repository.releases().approved(key, home) && repository.releases().approved(key, sker));
        assertEquals(Set.of(home, sker), new HashSet<>(planned));
        var release = repository.releases().get(key).orElseThrow();
        assertEquals(1L, release.get("version"));
        assertNull(release.get("pendingReconcile"));
        assertTrue(approvals.confirmRelease("owner", home, true, token).startsWith("Another approval"), "a token commits once");
    }

    @Test void sendWithoutPingIsKeptInTheSnapshotAndSurvivesACrashBeforeReconcile() {
        String token = preview("owner", true);
        var commit = repository.releases().commit(key, token, "owner", approvals.members(offer(home), true, NOW), NOW, true);
        assertTrue(commit.snapshot().orElseThrow().quiet());
        approvals.recover(); // as housekeeping after a crash
        assertTrue(repository.quiet(home) && repository.quiet(sker), "both games of the release post without a ping");
    }

    @Test void aSingleApprovalWithoutPingIsSavedInTheSameWrite() {
        offers.updateOne(eq("_id", home), unset("groupKey")); // a game outside any release
        assertTrue(repository.approve(home, "owner", NOW, true).isPresent());
        assertTrue(repository.quiet(home));
        assertTrue(repository.approve(home, "owner", NOW, false).isEmpty(), "approves once");
        assertTrue(repository.quiet(home));
        assertFalse(repository.quiet(sker));
    }

    @Test void aGameAddedOrChangedDuringReviewMakesTheConfirmationStale() {
        String token = preview("owner", true);
        offers.updateOne(eq("_id", sker), inc("revision", 1L)); // e.g. official details refreshed the review
        assertTrue(approvals.confirmRelease("owner", home, true, token).startsWith("The release changed"));
        assertEquals(FreebieOffer.State.PENDING, state(home));
        assertTrue(planned.isEmpty());

        token = preview("owner", false); // only Home previewed...
        offers.updateOne(eq("_id", sker), set("state", "PENDING"));
        assertTrue(approvals.confirmRelease("owner", home, true, token).startsWith("The release changed"),
                "...but the whole release confirmed: membership differs");
        assertFalse(repository.releases().approved(key, home));
    }

    @Test void expiredPreviewsOtherOwnersAndReplacedPreviewsApproveNothing() {
        String token = preview("owner", true);
        assertTrue(approvals.confirmRelease("intruder", home, true, token).startsWith("Only the owner"));
        String second = preview("other-owner", true);
        assertTrue(approvals.confirmRelease("owner", home, true, token).startsWith("Another approval"), "replaced by a newer preview");
        var later = new FreebieApprovals(repository, mock(FreebieSubscriptions.class), mock(FreebieReviewChannel.class), FreebieFeatures.DEFAULT,
                offer -> planned.add(offer.id()), Clock.fixed(NOW.plus(Duration.ofMinutes(6)), ZoneOffset.UTC));
        assertTrue(later.confirmRelease("other-owner", home, true, second).startsWith("This confirmation expired"));
        assertEquals(FreebieOffer.State.PENDING, state(home));
        assertTrue(planned.isEmpty());
    }

    @Test void concurrentConfirmationsCommitExactlyOnce() throws Exception {
        String token = preview("owner", true);
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<String>> clicks = new ArrayList<>();
            for (int i = 0; i < 8; i++) clicks.add(() -> approvals.confirmRelease("owner", home, true, token));
            long committed = 0;
            for (var f : pool.invokeAll(clicks)) if (f.get().startsWith("Approved")) committed++;
            assertEquals(1, committed);
        }
        assertEquals(1L, repository.releases().get(key).orElseThrow().get("version"));
        assertEquals(1, repository.releases().get(key).orElseThrow().getList("snapshots", Document.class).size());
    }

    @Test void aCrashAfterCommitIsReconciledByHousekeepingOnceAndIdempotently() {
        String token = preview("owner", true);
        var commit = repository.releases().commit(key, token, "owner", approvals.members(offer(home), true, NOW), NOW);
        assertEquals(FreebieReleases.Outcome.COMMITTED, commit.outcome());
        // Crash: nothing reconciled yet. Per-game state does not authorize anything on its own.
        assertEquals(FreebieOffer.State.PENDING, state(home));
        approvals.recover();
        assertEquals(FreebieOffer.State.APPROVED, state(home));
        assertEquals(FreebieOffer.State.APPROVED, state(sker));
        long revision = offer(home).revision();
        approvals.recover();
        assertEquals(revision, offer(home).revision(), "a second recovery changes nothing");
        assertEquals(2, planned.size());
    }

    @Test void aMemberChangedBetweenCommitAndReconcileIsWithdrawnNotApproved() {
        String token = preview("owner", true);
        var commit = repository.releases().commit(key, token, "owner", approvals.members(offer(home), true, NOW), NOW);
        offers.updateOne(eq("_id", sker), inc("revision", 1L));
        approvals.reconcile(commit.snapshot().orElseThrow());
        assertEquals(FreebieOffer.State.APPROVED, state(home));
        assertEquals(FreebieOffer.State.PENDING, state(sker));
        assertFalse(repository.releases().approved(key, sker));
        assertEquals(List.of(home), planned);
    }

    @Test void aLateGameNeedsItsOwnSnapshotAndJoinsTheSameRelease() {
        offers.updateOne(eq("_id", sker), set("state", "REJECTED"));
        String first = preview("owner", true);
        approvals.confirmRelease("owner", home, true, first);
        offers.updateOne(eq("_id", sker), set("state", "PENDING")); // a game discovered later in the same release
        assertFalse(repository.releases().approved(key, sker), "never approved by the earlier snapshot");
        String second = repository.releases().preview(key, approvals.members(offer(sker), false, NOW), "owner", NOW).orElseThrow();
        assertTrue(approvals.confirmRelease("owner", sker, false, second).startsWith("Approved 1"));
        assertEquals(Optional.of(key), repository.routes().announcementKey(sker));
        assertEquals(2L, repository.releases().get(key).orElseThrow().get("version"));
    }

    @Test void perGameApprovalCannotAuthorizeAReleaseGame() {
        assertTrue(approvals.confirmSingle("owner", home).startsWith("This game belongs to an Epic release"));
        assertEquals(FreebieOffer.State.PENDING, state(home));
        // Even if per-game state says APPROVED (legacy data, manual edit), a release-keyed route plans nothing.
        repository.routes().route(home, true, Optional.of(key));
        offers.updateOne(eq("_id", home), set("state", "APPROVED"));
        assertFalse(approvals.authorized(offer(home)));
        assertFalse(FreebieAnnouncementSender.authorized(repository, key, home));
    }

    @Test void endedGamesAreNotApprovedAndStopsStayRestrictive() {
        String token = preview("owner", true);
        offers.updateOne(eq("_id", sker), set("endsAt", Date.from(NOW.minusSeconds(1))));
        assertTrue(approvals.confirmRelease("owner", home, true, token).startsWith("The release changed"), "an ended game changes the list");
        String again = preview("owner", true);
        assertTrue(approvals.confirmRelease("owner", home, true, again).startsWith("Approved 1"));
        repository.stopByOwner(home, FreebieOffer.State.APPROVED, "owner", NOW);
        assertEquals(FreebieAnnouncementCards.Status.WITHDRAWN,
                FreebieAnnouncementSender.status(offer(home), repository.stoppedByOwner(home), NOW));
    }
}
