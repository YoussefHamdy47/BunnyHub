package org.bunnys.bunnynexus.alerts.adapters.mongo;

import java.util.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import com.mongodb.client.model.UpdateOptions;
import org.bunnys.bunnynexus.alerts.runtime.GamerPowerPoller;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoTestSupport.*;

class MongoPollingSchemaTest {
    @Test void unversionedPausedStateCannotBeSilentlyResetByProvisioning() {
        var h = new MongoTestSupport(); var schema = h.collection(MongoAlertSchema.SCHEMA); var sources = h.collection(MongoCatalogSchema.SOURCES);
        doReturn(find(new Document("schemaVersion", 1).append("checksum", MongoPollingSchema.checksum()))).when(schema).find(any(Bson.class));
        doReturn(find(new Document("schemaVersion", 1).append("pollPaused", true).append("nextPollAt", Date.from(NOW))))
                .when(sources).find(any(Bson.class));
        assertThrows(IllegalStateException.class, () -> new MongoPollingSchema(h.db).provision(Set.of(GamerPowerPoller.SOURCE)));
        verify(sources, never()).updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class));
    }
    @Test void absentAndDriftedMarkersFailClosed() {
        var h = new MongoTestSupport(); var schema = h.collection(MongoAlertSchema.SCHEMA);
        doReturn(find()).when(schema).find(any(Bson.class));
        assertThrows(RuntimeException.class, () -> new MongoPollingSchema(h.db).verifyInstalled());
        doReturn(find(new Document("schemaVersion", 1).append("checksum", "drift"))).when(schema).find(any(Bson.class));
        assertThrows(IllegalStateException.class, () -> new MongoPollingSchema(h.db).verifyInstalled());
        verify(schema, never()).updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class));
    }
    @Test void provisioningNeverUpsertsResetsGenerationsOrOverwritesInstalledPollState() {
        var h = new MongoTestSupport(); var schema = h.collection(MongoAlertSchema.SCHEMA); var sources = h.collection(MongoCatalogSchema.SOURCES);
        doReturn(find(new Document("schemaVersion", 1).append("checksum", MongoPollingSchema.checksum()))).when(schema).find(any(Bson.class));
        var record = new Document("schemaVersion", 1).append("pollFormat", 1).append("pollPaused", true)
                .append("pollFailures", 4L).append("nextPollAt", Date.from(NOW));
        doReturn(find(record)).when(sources).find(any(Bson.class));
        new MongoPollingSchema(h.db).provision(Set.of(GamerPowerPoller.SOURCE));
        var filter = ArgumentCaptor.forClass(Bson.class); var update = ArgumentCaptor.forClass(Bson.class); var options = ArgumentCaptor.forClass(UpdateOptions.class);
        verify(sources).updateOne(filter.capture(), update.capture(), options.capture());
        assertTrue(bson(filter.getValue()).contains("$exists")); assertTrue(bson(filter.getValue()).contains("false"));
        assertFalse(bson(update.getValue()).contains("generation")); assertFalse(bson(update.getValue()).contains("leaseUntil"));
        assertFalse(options.getValue().isUpsert()); assertEquals("simple", options.getValue().getCollation().getLocale());
        doReturn(find()).when(sources).find(any(Bson.class));
        assertThrows(RuntimeException.class, () -> new MongoPollingSchema(h.db).provision(Set.of(GamerPowerPoller.SOURCE)));
    }
}

