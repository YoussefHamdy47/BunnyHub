package org.bunnys.bunnynexus.freebies;

import org.junit.jupiter.api.*;
import org.bson.Document;
import com.mongodb.client.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Fanatical next to GamerPower, against a real replica set: the game GamerPower lists later is not reviewed twice. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreebieStoreSourcesIT {
    LocalReplicaSet replica;
    FreebieRepository repository;
    MongoCollection<Document> offers;
    final Instant now = Instant.parse("2026-11-27T11:10:00Z");
    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() {
        var db = replica.client.getDatabase("stores_it_" + UUID.randomUUID().toString().replace("-", ""));
        repository = new FreebieRepository(db); repository.installIndexes(); offers = db.getCollection(FreebieRepository.OFFERS);
    }

    FreebieFeed.Candidate fanatical() {
        return FanaticalIntake.candidate(StoreSourcesTest.resource("fanatical/vertical-drop-heroes-hd.json"), "vertical-drop-heroes-hd", now).orElseThrow();
    }

    /** GamerPower's listing of the same giveaway, 19 minutes later (id 3859, its end with GamerPower's two-hour grace). */
    FreebieFeed.Candidate gamerPower() {
        URI open = URI.create("https://www.gamerpower.com/open/vertical-drop-heroes-hd-steam-key-giveaway");
        return new FreebieFeed.Candidate(FreebieFeed.Provider.GAMERPOWER, "3859", FreebieStore.STEAM, "PC, Steam",
                "Vertical Drop Heroes HD Steam Key Giveaway", "Grab a free Steam key.", "1. Login into your Fanatical account",
                Optional.of("$6.99"), Optional.empty(), open, open, Optional.empty(), Optional.of(Instant.parse("2026-11-29T01:59:00Z")),
                "Not specified by source");
    }

    @Test void fanaticalOpensTheReviewAndGamerPowerIsHeldForAnOwner() {
        assertTrue(repository.recordSeen(fanatical(), now));
        var offer = repository.offersIn(FreebieOffer.State.PENDING, 10).getFirst();
        assertTrue(offer.id().startsWith("fanatical:"));
        assertEquals("Fanatical", offer.sourceLabel());
        assertEquals(FreebieStore.STEAM, offer.store());
        assertFalse(repository.recordSeen(gamerPower(), now.plusSeconds(19 * 60)));
        assertEquals(1, offers.countDocuments(), "one review, not two");
        assertTrue(repository.sources().conflict().isPresent());
    }

    @Test void twoCompleteFanaticalChecksWithoutTheGameEndIt() {
        repository.recordSeen(fanatical(), now);
        assertTrue(repository.markMissing(FreebieFeed.Provider.FANATICAL, Set.of()).isEmpty());
        assertEquals(1, repository.markMissing(FreebieFeed.Provider.FANATICAL, Set.of()).size());
    }
}
