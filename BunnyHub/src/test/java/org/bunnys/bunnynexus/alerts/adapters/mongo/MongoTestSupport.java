package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.*;
import com.mongodb.client.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bunnys.bunnynexus.alerts.domain.*;
import java.time.*;
import java.util.*;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Driver-boundary doubles only: these tests do not emulate Mongo's transaction/isolation engine. */
final class MongoTestSupport {
    static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
    static final OfferKey KEY = new OfferKey(new StoreId("epic"), "campaign", new Market("EG"));
    static final DestinationRef DEST = new DestinationRef("100", "200", "destination", "incarnation");
    final MongoClient client = mock(MongoClient.class);
    final MongoDatabase database = mock(MongoDatabase.class, RETURNS_SELF);
    final ClientSession session = mock(ClientSession.class);
    final Map<String, MongoCollection<Document>> collections = new HashMap<>();
    final MongoAlertDatabase db;
    MongoTestSupport() {
        when(client.getDatabase("test_alerts")).thenReturn(database);
        when(database.getCollection(anyString())).thenAnswer(call -> collection(call.getArgument(0)));
        when(client.startSession(any(ClientSessionOptions.class))).thenReturn(session);
        when(session.withTransaction(any(), any(TransactionOptions.class))).thenAnswer(call -> ((TransactionBody<?>) call.getArgument(0)).execute());
        db = new MongoAlertDatabase(client, "test_alerts", Duration.ofSeconds(10));
    }
    @SuppressWarnings("unchecked") MongoCollection<Document> collection(String name) {
        return collections.computeIfAbsent(name, ignored -> mock(MongoCollection.class));
    }
    @SuppressWarnings("unchecked") static FindIterable<Document> find(Document... documents) {
        FindIterable<Document> result = mock(FindIterable.class, RETURNS_SELF);
        when(result.first()).thenReturn(documents.length == 0 ? null : documents[0]);
        when(result.iterator()).thenAnswer(ignored -> {
            MongoCursor<Document> cursor = mock(MongoCursor.class);
            var iterator = Arrays.asList(documents).iterator();
            when(cursor.hasNext()).thenAnswer(call -> iterator.hasNext());
            when(cursor.next()).thenAnswer(call -> iterator.next());
            return cursor;
        });
        return result;
    }
    static Subscription subscription() { return new Subscription("sub", DEST, KEY.store(), KEY.market(), Topic.FREE_GAME, true, true, true, NOW, 1, Set.of("300")); }
    static Document guild() { return new Document("_id", "100").append("schemaVersion", 1).append("enabled", true).append("revision", 1L); }
    static Document destination() { return new Document("_id", "destination").append("schemaVersion", 1).append("guildId", "100")
            .append("channelId", "200").append("incarnation", "incarnation").append("enabled", true).append("revision", 1L); }
    static FanoutPlan plan() { return FanoutPlan.automatic("plan", "event", new FreeGameKey(KEY), NOW).claim("owner", NOW, NOW.plusSeconds(60)); }
    static DeliveryJob sending() {
        var job = DeliveryJob.ready("job", new DeliveryKey("event", "200"), DEST, "sub", NOW).claim("owner", NOW, NOW.plusSeconds(60));
        return job.authorize(job.lease().orElseThrow(), NOW, new DeliveryJob.Attempt("attempt", 1, "nonce", 1, 1,
                "v1", "a".repeat(64), Set.of("300"), NOW, NOW.plusSeconds(50)));
    }
    static Document checked(Document d, Instant now) { return new Document(d).append("checkedAt", Date.from(now)); }
    static String bson(Bson bson) { return bson.toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry()).toJson(); }
}
