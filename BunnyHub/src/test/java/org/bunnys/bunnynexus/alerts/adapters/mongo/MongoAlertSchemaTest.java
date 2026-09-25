package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.*;
import com.mongodb.client.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoTestSupport.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertSchema.*;

class MongoAlertSchemaTest {
    private MongoTestSupport installed(boolean hideIndex) {
        var h = new MongoTestSupport();
        when(h.collection(SCHEMA).find(any(Bson.class))).thenAnswer(ignored -> find(new Document("_id", MIGRATION)
                .append("schemaVersion", 1).append("checksum", checksum())));
        when(h.collection(BACKLOG).find(any(Bson.class))).thenAnswer(ignored -> find(new Document("_id", "delivery")
                .append("schemaVersion", 1).append("revision", 1L).append("pendingJobs", 0L).append("maxJobs", 1000L).append("paused", false)));
        for (String collection : indexes().stream().map(IndexSpec::collection).distinct().toList()) {
            List<Document> actual = indexes().stream().filter(spec -> spec.collection().equals(collection)).map(spec -> {
                var d = new Document("name", spec.name()).append("key", spec.keys()).append("unique", spec.unique());
                if (spec.automaticOnly()) d.append("partialFilterExpression", new Document("kind", "AUTOMATIC_FREE_GAME"));
                if (hideIndex && spec.name().equals("due_jobs")) d.append("hidden", true);
                return d;
            }).toList();
            when(h.collection(collection).listIndexes()).thenAnswer(ignored -> indexList(actual));
        }
        return h;
    }
    @SuppressWarnings("unchecked") private ListIndexesIterable<Document> indexList(List<Document> docs) {
        var iterable = (ListIndexesIterable<Document>) mock(ListIndexesIterable.class, RETURNS_SELF);
        when(iterable.iterator()).thenAnswer(ignored -> {
            var iterator = docs.iterator();
            var cursor = (MongoCursor<Document>) mock(MongoCursor.class);
            when(cursor.hasNext()).thenAnswer(call -> iterator.hasNext());
            when(cursor.next()).thenAnswer(call -> iterator.next());
            return cursor;
        });
        return iterable;
    }
    @Test void additiveManifestPreservesEventChannelAndTopicUniquenessWithoutTtl() {
        assertTrue(checksum().matches("[a-f0-9]{64}"));
        var jobs = indexes().stream().filter(i -> i.name().equals("event_channel_unique")).findFirst().orElseThrow();
        assertTrue(jobs.unique()); assertEquals(List.of("eventId", "channelId"), jobs.fields());
        var subscriptions = indexes().stream().filter(i -> i.name().equals("destination_store_topic_unique")).findFirst().orElseThrow();
        assertTrue(subscriptions.fields().contains("topic"));
        assertTrue(indexes().stream().allMatch(i -> i.collection().startsWith("Alert")));
        assertTrue(indexes().stream().allMatch(i -> i.options().getExpireAfter(TimeUnit.SECONDS) == null));
    }
    @Test void verifiesIndexesAndDoesNotRerunInstalledMigrationOrResetCapacity() {
        var h = installed(false); new MongoAlertSchema(h.db).install(2000);
        for (var collection : h.collections.values()) {
            verify(collection, never()).createIndex(any(Bson.class), any());
            verify(collection, never()).updateOne(any(Bson.class), any(Bson.class), any());
        }
    }
    @Test void missingIncompatibleOrHiddenSchemaFailsWithoutRepair() {
        var h = installed(true);
        assertThrows(IllegalStateException.class, () -> new MongoAlertSchema(h.db).verifyInstalled());
        var missing = new MongoTestSupport();
        when(missing.collection(SCHEMA).find(any(Bson.class))).thenAnswer(ignored -> find());
        assertThrows(IllegalStateException.class, () -> new MongoAlertSchema(missing.db).verifyInstalled());
    }
    @Test void freshInstallerRefusesToInitializeZeroCounterOverExistingJobs() {
        var h = new MongoTestSupport();
        when(h.collection(SCHEMA).find(any(Bson.class))).thenAnswer(ignored -> find());
        when(h.collection(BACKLOG).find(any(Bson.class))).thenAnswer(ignored -> find());
        when(h.collection(DELIVERIES).find()).thenAnswer(ignored -> find(new Document("_id", "existing")));
        assertThrows(IllegalStateException.class, () -> new MongoAlertSchema(h.db).install(1000));
        verify(h.collection(DELIVERIES), never()).createIndex(any(Bson.class), any());
    }
    @Test void transactionBudgetsCoverWholeCallbackAndUseSnapshotMajority() {
        var h = new MongoTestSupport(); assertEquals("done", h.db.transaction(session -> "done"));
        var options = ArgumentCaptor.forClass(TransactionOptions.class);
        verify(h.session).withTransaction(any(), options.capture());
        assertEquals(ReadConcern.SNAPSHOT, options.getValue().getReadConcern());
        assertEquals(WriteConcern.MAJORITY, options.getValue().getWriteConcern());
        assertEquals(10000L, options.getValue().getTimeout(TimeUnit.MILLISECONDS));
        verify(h.session).close();
        assertThrows(IllegalArgumentException.class, () -> new MongoAlertDatabase(h.client, "test_alerts", Duration.ZERO));
    }
}
