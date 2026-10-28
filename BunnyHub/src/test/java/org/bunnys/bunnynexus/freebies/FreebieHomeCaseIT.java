package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.mongodb.client.model.Filters.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The original Home duplicate, replayed from unmodified Epic and GamerPower responses captured 2026-10-24 with the
 * shipping defaults (GamerPower redirect resolution off). Each scenario states which outcome it gets:
 * MERGED (one canonical giveaway with both sources' evidence), HELD (one reviewable giveaway; the other report is an
 * open owner question and sends nothing) or INDEPENDENT (two reviewable giveaways).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreebieHomeCaseIT {
    static final Instant CAPTURED = Instant.parse("2026-10-24T16:52:25Z");
    enum Outcome { MERGED, HELD, INDEPENDENT }
    LocalReplicaSet replica;
    MongoDatabase db;
    FreebieRepository repository;
    MongoCollection<Document> offers;

    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() {
        db = replica.client.getDatabase("home_it_" + UUID.randomUUID().toString().replace("-", ""));
        repository = new FreebieRepository(db); repository.installIndexes(); offers = db.getCollection(FreebieRepository.OFFERS);
        assertFalse(FreebieFeatures.DEFAULT.resolveGamerPowerLinks(), "these scenarios use the shipping default");
    }

    static byte[] resource(String name) throws Exception {
        try (var in = FreebieHomeCaseIT.class.getResourceAsStream("/home/" + name)) { return in.readAllBytes(); }
    }
    static FreebieFeed.Candidate epic(String title) throws Exception {
        var result = new EpicIntake().decode(resource("epic-2026-10-24-US.json"), CAPTURED);
        assertEquals(FreebieFeed.State.COMPLETE, result.state());
        return result.candidates().stream().filter(c -> c.title().equals(title)).findFirst().orElseThrow();
    }
    static FreebieFeed.Candidate gamerpower(String id) throws Exception {
        var batch = new GamerPowerIntake().decode(200, resource("gamerpower-2026-10-24.json"), CAPTURED);
        return batch.candidates().stream().filter(c -> c.sourceItemId().equals(id)).map(FreebieFeed.Candidate::from).findFirst().orElseThrow();
    }
    /** Discovery with defaults: no storefront resolution is applied to GamerPower reports. */
    boolean discover(FreebieFeed.Candidate c, Instant at) { return repository.recordSeen(c, at); }

    Outcome outcome() {
        long reviewable = offers.countDocuments(in("state", "PENDING", "APPROVED", "COMPLETED"));
        if (reviewable == 2) return Outcome.INDEPENDENT;
        assertEquals(1, reviewable);
        var row = offers.find().first();
        if (row.get("evidence", Document.class).keySet().containsAll(Set.of("epic", "gamerpower"))) return Outcome.MERGED;
        assertEquals(1, repository.sources().openQuestions(), "the other report is an open owner question");
        return Outcome.HELD;
    }

    @Test void capturedHomeReportsDifferInTitlePlatformsAndDeadline() throws Exception {
        var e = epic("Home - A Unique Horror Adventure");
        var g = gamerpower("3823");
        assertEquals(Instant.parse("2026-10-29T15:00:00Z"), e.endsAt().orElseThrow());
        assertEquals("Home (Epic Games) Giveaway", g.title());
        assertEquals("PC, Android, iOS, Epic Games Store", g.platforms());
        assertTrue(g.productSlug().isEmpty(), "GamerPower's own link carries no storefront identity");
        assertEquals(Optional.of("home-19212a"), e.productSlug());
    }

    @Test void epicFirstThenGamerPowerFortyMinutesLaterIsHeldNotMerged() throws Exception {
        assertTrue(discover(epic("Home - A Unique Horror Adventure"), CAPTURED));
        assertFalse(discover(gamerpower("3823"), CAPTURED.plus(Duration.ofMinutes(40))), "no new review");
        assertEquals(Outcome.HELD, outcome());
        assertEquals("Home - A Unique Horror Adventure", offers.find().first().getString("title"));
        assertEquals(1, repository.sources().unannounced(10).size(), "the question is queued for the review channel");
    }

    @Test void gamerPowerFirstThenEpicFortyMinutesLaterIsHeldOnTheGamerPowerReview() throws Exception {
        assertTrue(discover(gamerpower("3823"), CAPTURED));
        assertFalse(discover(epic("Home - A Unique Horror Adventure"), CAPTURED.plus(Duration.ofMinutes(40))));
        assertEquals(Outcome.HELD, outcome());
        assertEquals("gamerpower:3823", offers.find().first().getString("_id"));
    }

    @Test void simultaneousArrivalIsHeldOnceWhicheverWins() throws Exception {
        var e = epic("Home - A Unique Horror Adventure"); var g = gamerpower("3823");
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) { var c = i % 2 == 0 ? e : g; tasks.add(() -> discover(c, CAPTURED)); }
            int created = 0;
            for (var f : pool.invokeAll(tasks)) if (f.get()) created++;
            assertEquals(1, created);
        }
        assertEquals(Outcome.HELD, outcome());
    }

    @Test void heldReportsSendNothingUntilTheOwnerDecidesAndBothDecisionsWork() throws Exception {
        discover(epic("Home - A Unique Horror Adventure"), CAPTURED);
        var g = gamerpower("3823");
        discover(g, CAPTURED);
        String parent = offers.find().first().getString("_id");
        String token = FreebieMessages.reference(g.identity());
        repository.sources().resolve(parent, token, "same", "owner", CAPTURED);
        discover(g, CAPTURED.plus(Duration.ofMinutes(10)));
        assertEquals(Outcome.MERGED, outcome(), "after Same giveaway, the next observation joins its evidence");
        assertEquals(0, repository.sources().openQuestions());

        fresh();
        discover(epic("Home - A Unique Horror Adventure"), CAPTURED);
        discover(g, CAPTURED);
        repository.sources().resolve(offers.find().first().getString("_id"), token, "separate", "owner", CAPTURED);
        assertTrue(discover(g, CAPTURED.plus(Duration.ofMinutes(10))), "a legitimately separate giveaway gets its own review");
        assertEquals(Outcome.INDEPENDENT, outcome());
    }

    @Test void skerRitualFromTheSameCapturesIsAlsoHeldAndADifferentGameIsIndependent() throws Exception {
        discover(epic("Sker Ritual"), CAPTURED);
        assertFalse(discover(gamerpower("3824"), CAPTURED));
        assertEquals(Outcome.HELD, outcome());
        assertTrue(discover(gamerpower("3820"), CAPTURED), "GetHigh.EXE is another game: its own review");
    }

    // Synthetic mutation of the captured GamerPower row: no end date ("N/A").
    @Test void aReportWithoutAnEndDateCannotSkipTheOwnerQuestion() throws Exception {
        discover(epic("Home - A Unique Horror Adventure"), CAPTURED);
        var g = gamerpower("3823");
        var undated = new FreebieFeed.Candidate(g.provider(), g.sourceId(), g.store(), g.platforms(), g.title(), g.description(),
                g.instructions(), g.worth(), g.image(), g.claimPage(), g.sourcePage(), Optional.empty(), Optional.empty(), g.region());
        assertFalse(discover(undated, CAPTURED));
        assertEquals(Outcome.HELD, outcome());
    }

    @Test void announcedQuestionsStayOpenAndCountedUntilDecided() throws Exception {
        discover(epic("Home - A Unique Horror Adventure"), CAPTURED);
        discover(gamerpower("3823"), CAPTURED);
        var question = repository.sources().unannounced(10).getFirst();
        var view = FreebieSourceDecisions.view(question.parent(), question.token());
        assertTrue(view.text().contains("held"));
        assertEquals(3, view.rows().getFirst().getComponents().size(), "Same, Separate and Ignore");
        repository.sources().announced(question);
        assertTrue(repository.sources().unannounced(10).isEmpty(), "posted once");
        assertEquals(1, repository.sources().openQuestions(), "still visible in status until decided");
    }
}
