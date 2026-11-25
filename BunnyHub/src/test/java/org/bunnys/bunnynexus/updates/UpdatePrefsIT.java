package org.bunnys.bunnynexus.updates;

import com.mongodb.client.MongoDatabase;
import org.bunnys.bunnynexus.freebies.LocalReplicaSet;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real MongoDB: opt-out, sent history and the cooldown inputs, read one by one and in bulk. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UpdatePrefsIT {
    final Instant now = Instant.parse("2026-11-25T12:00:00Z");
    LocalReplicaSet replica;
    MongoDatabase db;

    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() { db = replica.client.getDatabase("updates_it_" + UUID.randomUUID().toString().replace("-", "")); }

    @Test void choicesAndHistoryAreKeptPerServer() {
        var prefs = new UpdatePrefs(db);
        assertEquals(UpdateAudience.Prefs.NONE, prefs.get("1"));
        prefs.sent("1", "deals-board", now);
        prefs.sent("1", "deals-board", now.plusSeconds(5));
        prefs.setOff("2", true, "42", now);
        var both = prefs.get(List.of("1", "2", "3"));
        assertEquals(new UpdateAudience.Prefs(false, Optional.of(now.plusSeconds(5)), Set.of("deals-board")), both.get("1"));
        assertTrue(both.get("2").off());
        assertEquals(UpdateAudience.Prefs.NONE, both.get("3"));
        prefs.setOff("2", false, "42", now);
        assertFalse(prefs.get("2").off());
        assertEquals(UpdateAudience.PromoState.NONE, prefs.promoState("9"));
        prefs.promoShown("1", "deals-board", now);
        prefs.promoShown("1", "deals-board", now.plusSeconds(60));
        assertEquals(new UpdateAudience.PromoState(Optional.of(now.plusSeconds(60)), Map.of("deals-board", 2)), prefs.promoState("1"));
        prefs.forget("1");
        assertEquals(UpdateAudience.Prefs.NONE, prefs.get("1"));
    }
}
