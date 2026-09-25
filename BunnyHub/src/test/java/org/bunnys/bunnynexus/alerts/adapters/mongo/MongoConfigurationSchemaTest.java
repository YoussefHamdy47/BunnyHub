package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.client.*;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoTestSupport.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertSchema.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MongoConfigurationSchemaTest {
    @SuppressWarnings("unchecked") private static ListIndexesIterable<Document> indexList(List<IndexSpec> specs) {
        var result = (ListIndexesIterable<Document>) mock(ListIndexesIterable.class, RETURNS_SELF);
        when(result.iterator()).thenAnswer(ignored -> {
            var docs = specs.stream().map(s -> new Document("name", s.name()).append("key", s.keys()).append("unique", s.unique())
                    .append("partialFilterExpression", s.options().getPartialFilterExpression())).toList();
            return find(docs.toArray(Document[]::new)).iterator();
        });
        return result;
    }
    private static MongoTestSupport fixture(boolean installed) {
        var h = new MongoTestSupport();
        when(h.collection(SCHEMA).find(any(Bson.class))).thenAnswer(call -> {
            boolean base = bson(call.getArgument(0)).contains(MongoAlertSchema.MIGRATION);
            if (!base && !installed) return find();
            return find(new Document("_id", base ? MongoAlertSchema.MIGRATION : MongoConfigurationSchema.MIGRATION)
                    .append("schemaVersion", 1).append("checksum", base ? MongoAlertSchema.checksum() : MongoConfigurationSchema.checksum()));
        });
        when(h.collection(BACKLOG).find(any(Bson.class))).thenAnswer(call -> find(new Document("schemaVersion", 1)
                .append("pendingJobs", 0L).append("maxJobs", 100L).append("paused", false)));
        var specs = new ArrayList<>(MongoAlertSchema.indexes()); specs.addAll(MongoConfigurationSchema.indexes());
        for (String collection : specs.stream().map(IndexSpec::collection).distinct().toList()) {
            when(h.collection(collection).listIndexes()).thenAnswer(call -> indexList(specs.stream().filter(s -> s.collection().equals(collection)).toList()));
            when(h.collection(collection).find()).thenAnswer(call -> find());
        }
        return h;
    }
    @Test void installedMigrationOnlyVerifiesAndDoesNotRewriteOldSchema() {
        var h = fixture(true); new MongoConfigurationSchema(h.db).install();
        verify(h.collection(SCHEMA), never()).updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class));
        for (var collection : h.collections.values()) verify(collection, never()).createIndex(any(Bson.class), any());
    }
    @Test void refusesToInventAnAggregateOverExistingConfiguration() {
        var h = fixture(false);
        when(h.collection(GUILDS).find()).thenAnswer(call -> find(new Document("_id", "100")));
        assertThrows(IllegalStateException.class, () -> new MongoConfigurationSchema(h.db).install());
        verify(h.collection(SCHEMA), never()).updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class));
        verify(h.collection(MongoConfigurationSchema.AUDIT), never()).createIndex(any(Bson.class), any());
    }
    @Test void missingMarkerFailsWithoutCreatingCollectionsOrApplyingMigration() {
        var h = fixture(false);
        assertThrows(IllegalStateException.class, () -> new MongoConfigurationSchema(h.db).verifyInstalled());
        verify(h.collection(SCHEMA), never()).updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class));
    }
}
