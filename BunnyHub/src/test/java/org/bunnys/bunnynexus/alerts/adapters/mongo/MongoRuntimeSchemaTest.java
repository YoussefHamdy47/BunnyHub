package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bunnys.bunnynexus.alerts.application.OwnershipRepository;
import org.bunnys.bunnynexus.alerts.domain.AlertIdentity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoTestSupport.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertSchema.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MongoRuntimeSchemaTest {
    @SuppressWarnings("unchecked") private MongoTestSupport installed() {
        var h = new MongoTestSupport();
        when(h.collection(SCHEMA).find(any(Bson.class))).thenAnswer(call -> find(new Document("schemaVersion", 1).append("checksum", MongoRuntimeSchema.checksum())));
        when(h.collection(MongoRuntimeSchema.OWNERSHIP).find(any(Bson.class))).thenAnswer(call -> find(new Document("_id", "egress")
                .append("schemaVersion", 1).append("generation", 3L).append("revision", 20L).append("leaseUntil", Date.from(NOW.plusSeconds(30)))));
        for (var index : MongoRuntimeSchema.indexes()) {
            var iterable = (ListIndexesIterable<Document>) mock(ListIndexesIterable.class, RETURNS_SELF);
            when(iterable.iterator()).thenAnswer(call -> find(new Document("name", index.name()).append("key", index.keys())).iterator());
            when(h.collection(index.collection()).listIndexes()).thenReturn(iterable);
        }
        return h;
    }
    @Test void sourceProvisioningNeverResetsExistingTokensGenerationsOrProgress() {
        var h = installed(); var schema = new MongoRuntimeSchema(h.db);
        var source = new OwnershipRepository.Source(new AlertIdentity.SourceId("gamerpower"), "EG");
        schema.provisionSources(Set.of(source));
        var update = ArgumentCaptor.forClass(Bson.class); var options = ArgumentCaptor.forClass(UpdateOptions.class);
        verify(h.collection(MongoCatalogSchema.SOURCES)).updateOne(any(Bson.class), update.capture(), options.capture());
        String json = bson(update.getValue()); assertTrue(json.contains("$setOnInsert")); assertFalse(json.contains("$unset"));
        assertFalse(json.contains("leaseToken")); assertTrue(options.getValue().isUpsert());
        verify(h.collection(MongoRuntimeSchema.OWNERSHIP), never()).updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class));
    }
    @Test void missingMigrationCannotSilentlyCreateOwnershipRecords() {
        var h = new MongoTestSupport(); when(h.collection(SCHEMA).find(any(Bson.class))).thenAnswer(call -> find());
        assertThrows(IllegalStateException.class, () -> new MongoRuntimeSchema(h.db).verifyInstalled());
        verify(h.collection(SCHEMA), never()).updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class));
    }
    @Test void schemaVerificationPreservesActiveOwnershipAndRejectsBadCounters() {
        var h = installed(); new MongoRuntimeSchema(h.db).verifyInstalled();
        when(h.collection(MongoRuntimeSchema.OWNERSHIP).find(any(Bson.class))).thenAnswer(call -> find(new Document("schemaVersion", 1)
                .append("generation", -1L).append("revision", 1L).append("leaseUntil", Date.from(NOW))));
        assertThrows(IllegalStateException.class, () -> new MongoRuntimeSchema(h.db).verifyInstalled());
        verify(h.collection(MongoRuntimeSchema.OWNERSHIP), never()).updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class));
    }
}
