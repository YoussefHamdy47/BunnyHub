package org.bunnys.bunnynexus.alerts.adapters.mongo;

import org.bson.Document;
import org.bunnys.bunnynexus.alerts.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoTestSupport.*;

class AlertDocumentsTest {
    @Test void roundTripsSendingAndUncertainSnapshotsWithoutLosingAttemptEvidence() {
        for (var job : List.of(sending(), sending().recoverExpired(NOW.plusSeconds(60)))) {
            Document stored = AlertDocuments.delivery(job);
            // Actual BSON/JSON scalar types are decoded again instead of returning the same Java objects.
            var decoded = AlertDocuments.delivery(Document.parse(stored.toJson()));
            assertEquals(job.snapshot(), decoded.snapshot());
        }
    }
    @Test void planRoundTripPreservesCursorLeaseAndCounters() {
        var p = plan().finishPage(plan().lease().orElseThrow(), NOW, Optional.of("cursor"), 3, 2, 1)
                .claim("next", NOW, NOW.plusSeconds(60));
        assertEquals(p, AlertDocuments.plan(Document.parse(AlertDocuments.plan(p).toJson())));
    }
    @Test void rejectsFutureSchemasFractionalIntegersAndMalformedAttemptState() {
        var stored = AlertDocuments.delivery(sending());
        assertThrows(IllegalArgumentException.class, () -> AlertDocuments.delivery(new Document(stored).append("schemaVersion", 2)));
        assertThrows(IllegalArgumentException.class, () -> AlertDocuments.delivery(new Document(stored).append("generation", 1.5)));
        var missing = new Document(stored); missing.remove("attempt");
        assertThrows(IllegalArgumentException.class, () -> AlertDocuments.delivery(missing));
        assertThrows(IllegalArgumentException.class, () -> AlertDocuments.plan(new Document(AlertDocuments.plan(plan())).append("kind", "FUTURE_KIND")));
    }
    @Test void rolesAndTimestampPrecisionFailClosed() {
        var doc = AlertDocuments.attempt(sending().attempt().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> AlertDocuments.attempt(new Document(doc).append("roleIds", List.of("300", "300"))));
        assertThrows(IllegalArgumentException.class, () -> AlertDocuments.attempt(new Document(doc).append("roleIds", Collections.nCopies(101, "300"))));
        assertThrows(IllegalArgumentException.class, () -> AlertDocuments.date(NOW.plusNanos(1)));
    }
    @Test void joinsCurrentGuardsAndRejectsCrossGuildOrRecreatedDestination() {
        var sub = AlertDocuments.subscription(subscription());
        assertFalse(sub.containsKey("guildEnabled"));
        assertEquals(subscription(), AlertDocuments.subscription(sub, guild(), destination()));
        assertFalse(AlertDocuments.subscription(sub, null, destination()).guildEnabled());
        assertFalse(AlertDocuments.subscription(sub, guild(), null).destinationEnabled());
        assertFalse(AlertDocuments.subscription(sub, guild(), new Document(destination()).append("guildId", "999")).destinationEnabled());
        assertFalse(AlertDocuments.subscription(sub, guild(), new Document(destination()).append("incarnation", "new")).destinationEnabled());
    }
}
