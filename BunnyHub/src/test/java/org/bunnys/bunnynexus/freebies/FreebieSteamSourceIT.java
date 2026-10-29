package org.bunnys.bunnynexus.freebies;

import org.junit.jupiter.api.*;
import org.bson.Document;
import com.mongodb.client.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Steam's store as a third source, against a real replica set, with the responses captured on 2026-10-29. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreebieSteamSourceIT {
    LocalReplicaSet replica;
    FreebieRepository repository;
    MongoCollection<Document> offers;
    final Instant now = Instant.parse("2026-10-29T17:40:00Z");
    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() {
        var db = replica.client.getDatabase("steam_it_" + UUID.randomUUID().toString().replace("-", ""));
        repository = new FreebieRepository(db); repository.installIndexes(); offers = db.getCollection(FreebieRepository.OFFERS);
    }

    FreebieFeed.Candidate steam() throws Exception {
        try (var input = getClass().getResourceAsStream("/steam/items-2026-10-29.json")) {
            return new SteamIntake().decode(input.readAllBytes(), List.of(785890L, 5340370L, 4693960L), true, now).candidates().getFirst();
        }
    }
    FreebieFeed.Candidate gamerPower() throws Exception {
        try (var input = getClass().getResourceAsStream("/gamerpower/all-giveaways-2026-10-29.json")) {
            var batch = new GamerPowerIntake().decode(200, input.readAllBytes(), now);
            return FreebieFeed.Candidate.from(batch.candidates().stream().filter(c -> c.sourceItemId().equals("3834")).findFirst().orElseThrow());
        }
    }

    @Test void aSteamReportOpensOneReviewUnderItsOwnSource() throws Exception {
        var c = steam();
        assertTrue(repository.recordSeen(c, now));
        assertFalse(repository.recordSeen(c, now), "seen again: no second review");
        var offer = repository.offersIn(FreebieOffer.State.PENDING, 10).getFirst();
        assertTrue(offer.id().startsWith("steam:"));
        assertEquals(FreebieStore.STEAM, offer.store());
        assertEquals("Steam", offer.sourceLabel());
        assertEquals(Instant.parse("2026-10-31T17:00:00Z"), offer.endsAt().orElseThrow());
        assertEquals("https://store.steampowered.com/app/785890/", offer.claimUrl().toString());
    }

    @Test void gamerPowerReportingTheSameGameLaterIsHeldForAnOwnerNotReviewedTwice() throws Exception {
        assertTrue(repository.recordSeen(steam(), now));
        assertFalse(repository.recordSeen(gamerPower(), now));
        assertEquals(1, offers.countDocuments());
        assertTrue(repository.sources().conflict().isPresent());
    }

    @Test void steamReportingAGameGamerPowerAlreadyHasIsHeldToo() throws Exception {
        assertTrue(repository.recordSeen(gamerPower(), now));
        assertFalse(repository.recordSeen(steam(), now));
        assertEquals(1, offers.countDocuments());
        assertTrue(repository.sources().conflict().isPresent());
    }

    @Test void twoCompleteSteamChecksWithoutTheGameEndIt() throws Exception {
        var c = steam();
        repository.recordSeen(c, now);
        assertTrue(repository.markMissing(FreebieFeed.Provider.STEAM, Set.of()).isEmpty(), "one miss is not enough");
        var gone = repository.markMissing(FreebieFeed.Provider.STEAM, Set.of());
        assertEquals(1, gone.size());
        assertTrue(gone.getFirst().id().startsWith("steam:"));
        assertTrue(repository.markMissing(FreebieFeed.Provider.GAMERPOWER, Set.of()).isEmpty(), "other sources never end a Steam report");
    }
}
