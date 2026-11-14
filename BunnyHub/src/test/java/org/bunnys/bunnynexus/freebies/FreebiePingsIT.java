package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real MongoDB: the per-channel ping budget (FREEBIE_REDESIGN.md step 4). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreebiePingsIT {
    static final String GUILD = "100000000000000000", CHANNEL = "200000000000000000", OTHER = "200000000000000001";
    final Instant now = Instant.parse("2026-11-10T12:00:00Z");
    LocalReplicaSet replica;
    MongoDatabase db;
    FreebiePings pings;

    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() {
        db = replica.client.getDatabase("pings_it_" + UUID.randomUUID().toString().replace("-", ""));
        pings = new FreebiePings(db).cooldown(Duration.ofMinutes(60));
    }

    @Test void twoDropsWithinTheCooldownGetOnePingAndARetryKeepsItsAnswer() {
        assertTrue(pings.take(GUILD, CHANNEL, "drop|a", now).allowed());
        var held = pings.take(GUILD, CHANNEL, "drop|b", now.plusSeconds(600));
        assertFalse(held.allowed(), "pinged ten minutes ago");
        assertEquals(Optional.of(now), held.lastPingAt());
        assertEquals("pinged 10 min ago", FreebiePings.since(held, now.plusSeconds(600)));
        assertTrue(pings.take(GUILD, CHANNEL, "drop|a", now.plusSeconds(900)).allowed(), "a retry or later lead page of the same drop");
        assertFalse(pings.take(GUILD, CHANNEL, "drop|b", now.plusSeconds(900)).allowed());
        assertTrue(pings.take(GUILD, OTHER, "drop|c", now.plusSeconds(600)).allowed(), "each channel has its own budget");
        assertTrue(pings.take(GUILD, CHANNEL, "drop|d", now.plus(Duration.ofMinutes(60))).allowed(), "the cooldown has passed");
        assertFalse(pings.take(GUILD, CHANNEL, "drop|a", now.plus(Duration.ofMinutes(61))).allowed(), "a newer ping now holds the channel");
        assertEquals(2, db.getCollection(FreebiePings.COLLECTION).countDocuments(), "one document per channel");
    }

    @Test void concurrentDropsInOneChannelPingOnce() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> takes = new ArrayList<>();
            for (int i = 0; i < 16; i++) { String key = "drop|" + i; takes.add(() -> pings.take(GUILD, CHANNEL, key, now).allowed()); }
            int allowed = 0;
            for (var f : pool.invokeAll(takes)) if (f.get()) allowed++;
            assertEquals(1, allowed, "one conditional write per channel admits one ping");
        }
        // And again once the channel's document exists and the cooldown has passed.
        Instant later = now.plus(Duration.ofHours(2));
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> takes = new ArrayList<>();
            for (int i = 0; i < 16; i++) { String key = "later|" + i; takes.add(() -> pings.take(GUILD, CHANNEL, key, later).allowed()); }
            int allowed = 0;
            for (var f : pool.invokeAll(takes)) if (f.get()) allowed++;
            assertEquals(1, allowed);
        }
    }

    @Test void aZeroCooldownAlwaysPingsAndWritesNothing() {
        pings.cooldown(Duration.ZERO);
        assertTrue(pings.take(GUILD, CHANNEL, "drop|a", now).allowed());
        assertTrue(pings.take(GUILD, CHANNEL, "drop|b", now).allowed());
        assertEquals(0, db.getCollection(FreebiePings.COLLECTION).countDocuments());
    }

    @Test void retentionRemovesOnlyLedgerEntriesOlderThanTheWindow() {
        pings.take(GUILD, CHANNEL, "old", now.minus(Duration.ofDays(40)));
        pings.take(GUILD, OTHER, "recent", now.minus(Duration.ofDays(2)));
        assertEquals(1, pings.purge(now.minus(Duration.ofDays(30)), 10));
        assertEquals(0, pings.purge(now.minus(Duration.ofDays(30)), 10), "idempotent");
        assertFalse(pings.take(GUILD, OTHER, "next", now.minus(Duration.ofDays(2)).plusSeconds(60)).allowed(), "the recent entry still holds");
        assertTrue(pings.take(GUILD, CHANNEL, "next", now).allowed(), "a purged channel pings again");
    }
}
