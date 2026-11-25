package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real MongoDB: one remembered picker per server, replaced by a newer one, forgotten when gone or on leave. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreebieRolePickersIT {
    static final String GUILD = "100000000000000000";
    final Instant now = Instant.parse("2026-11-23T12:00:00Z");
    LocalReplicaSet replica;
    MongoDatabase db;

    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() { db = replica.client.getDatabase("pickers_it_" + UUID.randomUUID().toString().replace("-", "")); }

    @Test void theNewestPickerIsRememberedAndForgottenWhenGone() {
        var subscriptions = new FreebieSubscriptions(db);
        var pickers = subscriptions.rolePickers();
        assertEquals(Optional.empty(), pickers.find(GUILD));
        pickers.posted(GUILD, "200", "300", now);
        pickers.posted(GUILD, "201", "301", now.plusSeconds(60));
        assertEquals(Optional.of(new FreebieRolePickers.Picker(GUILD, "201", "301")), pickers.find(GUILD));
        pickers.gone(GUILD, "300");
        assertTrue(pickers.find(GUILD).isPresent(), "an older message being gone does not forget the newer picker");
        pickers.gone(GUILD, "301");
        assertEquals(Optional.empty(), pickers.find(GUILD));
        pickers.posted(GUILD, "202", "302", now);
        subscriptions.removeGuild(GUILD);
        assertEquals(Optional.empty(), pickers.find(GUILD), "leaving the server forgets its picker");
    }
}
