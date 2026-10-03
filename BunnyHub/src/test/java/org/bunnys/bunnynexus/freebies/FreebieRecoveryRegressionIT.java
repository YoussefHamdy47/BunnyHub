package org.bunnys.bunnynexus.freebies;

import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.mongodb.client.model.Filters.eq;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreebieRecoveryRegressionIT {
    LocalReplicaSet replica;
    final Instant now = Instant.parse("2026-09-25T12:00:00Z");
    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }

    @Test void reservingNoticeDoesNotRecordDailySuccessBeforeSending() {
        var db = replica.client.getDatabase("notice_regression_" + UUID.randomUUID().toString().replace("-", ""));
        var repository = new FreebieRepository(db);
        repository.notices().enqueue(new org.bson.Document("_id", "delivery").append("guildId", "100000000000000001")
                .append("offerId", "gamerpower:1").append("channelId", "100000000000000099")
                .append("failure", "MISSING_PERMISSIONS").append("finishedAt", Date.from(now)), now);
        assertTrue(repository.notices().claim(now).isPresent());
        var saved = db.getCollection(FreebieRepository.NOTICES).find(eq("_id", "100000000000000001")).first();
        assertNotNull(saved);
        assertNull(saved.getDate("lastNoticeAt"), "reservation is not a successful notice");
    }

    @Test void staleReviewCallbackCannotReplaceNewWorkersResult() {
        var db = replica.client.getDatabase("review_regression_" + UUID.randomUUID().toString().replace("-", ""));
        var repository = new FreebieRepository(db);
        repository.recordSeen(FreebieRepositoryIT.candidate("1", "PC, Steam"), now);
        var old = repository.reviews().claim(now).orElseThrow();
        var current = repository.reviews().claim(now.plusSeconds(181)).orElseThrow();
        repository.reviews().destination(current, "100000000000000099", now.plusSeconds(181));
        repository.reviews().sent(current, "100000000000000099", "current", now.plusSeconds(181));
        repository.reviews().sent(old, "100000000000000099", "stale", now.plusSeconds(181));
        assertEquals(Optional.of("current"), repository.offer(old.offer().id()).orElseThrow().reviewMessageId());
    }
}
