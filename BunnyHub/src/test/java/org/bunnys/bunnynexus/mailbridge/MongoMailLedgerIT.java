package org.bunnys.bunnynexus.mailbridge;

import com.mongodb.client.MongoDatabase;
import org.bunnys.bunnynexus.freebies.LocalReplicaSet;
import org.bunnys.bunnynexus.mailbridge.MailLedger.Entry;
import org.bunnys.bunnynexus.mailbridge.MailLedger.Status;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The ledger against a real local replica set: what survives a restart, what is due, what expires. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoMailLedgerIT {
    LocalReplicaSet replica;
    MongoDatabase db;
    final Instant t0 = Instant.parse("2026-10-20T08:00:00Z");

    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() { db = replica.client.getDatabase("mail_" + UUID.randomUUID().toString().replace("-", "")); }

    MongoMailLedger ledger() {
        var ledger = new MongoMailLedger(db);
        ledger.installIndexes();
        return ledger;
    }

    @Test void theCutoffIsSetOnceAndSurvivesRestarts() {
        assertEquals(t0, ledger().cutoff(t0));
        assertEquals(t0, ledger().cutoff(t0.plus(Duration.ofDays(3))), "a later start doesn't move it");
    }

    @Test void entriesRoundTripAndOnlyUnfinishedOnesAreDue() {
        var ledger = ledger();
        ledger.save(new Entry("p", Status.PENDING, MailCategory.EEE_TRAINING, t0, 0, t0, Optional.empty(), "", t0));
        ledger.save(new Entry("s", Status.SENDING, MailCategory.RTL_VERIFICATION, t0.plusSeconds(1), 1, t0.plusSeconds(60), Optional.empty(), "posting", t0));
        ledger.save(new Entry("d", Status.SENT, MailCategory.POWER_ELECTRONICS, t0, 1, t0, Optional.of("123"), "posted", t0));
        var restarted = ledger();
        assertEquals(Optional.of("123"), restarted.find("d").orElseThrow().discordMessageId());
        assertEquals(java.util.List.of("p"), restarted.due(t0, 10).stream().map(Entry::id).toList());
        assertEquals(java.util.List.of("p", "s"), restarted.due(t0.plusSeconds(60), 10).stream().map(Entry::id).toList());
        assertEquals(1L, restarted.counts().get(Status.SENT));
        assertNull(db.getCollection(MongoMailLedger.COLLECTION).find(new org.bson.Document("_id", "p")).first().get("expiresAt"),
                "unfinished entries never expire");
        assertNotNull(db.getCollection(MongoMailLedger.COLLECTION).find(new org.bson.Document("_id", "d")).first().get("expiresAt"));
    }
}
