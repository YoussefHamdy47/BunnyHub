package org.bunnys.bunnynexus.alerts.adapters.mongo;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MongoIndexVerifierTest {
    @Test void compoundIndexOrderIsPartOfTheContract() {
        var spec = new MongoAlertSchema.IndexSpec("jobs", "due", List.of("state", "dueAt"), false, false);
        assertTrue(MongoIndexVerifier.matches(spec, new Document("key", spec.keys())));
        assertFalse(MongoIndexVerifier.matches(spec, new Document("key", new Document("dueAt", 1).append("state", 1))));
        for (String flag : List.of("hidden", "sparse", "unique"))
            assertFalse(MongoIndexVerifier.matches(spec, new Document("key", spec.keys()).append(flag, true)));
        assertFalse(MongoIndexVerifier.matches(spec, new Document("key", spec.keys()).append("expireAfterSeconds", 60)));
    }
    @Test void identityHashRetainsOriginalPersistedEncoding() {
        String expected = "e05b2ad28184a28ce4d9864b125e8fa527d2786520a0140dc94d86d0cfc4b961";
        assertEquals(expected, CatalogDocuments.id("alert-delivery-v1", "event-1", "200"));
        assertEquals(expected, org.bunnys.bunnynexus.alerts.application.FanoutEngine.deliveryId(
                new org.bunnys.bunnynexus.alerts.domain.AlertIdentity.DeliveryKey("event-1", "200")));
        assertNotEquals(CatalogDocuments.id("ns", "ab", "c"), CatalogDocuments.id("ns", "a", "bc"));
    }
}
