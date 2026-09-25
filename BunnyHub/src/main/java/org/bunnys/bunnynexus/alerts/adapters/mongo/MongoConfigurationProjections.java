package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.client.ClientSession;
import com.mongodb.client.model.*;
import org.bson.Document;
import org.bunnys.bunnynexus.alerts.domain.*;
import java.time.Instant;
import java.util.*;
import static com.mongodb.client.model.Filters.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertSchema.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoConfigurationSchema.*;

/** Writes only changed guards. Removals retain immutable identities separately from unique active slots. */
final class MongoConfigurationProjections {
    private final MongoAlertDatabase db;
    MongoConfigurationProjections(MongoAlertDatabase db) { this.db = db; }
    void write(ClientSession session, GuildConfiguration before, GuildConfiguration next, ConfigurationChange change, Instant now) {
        var oldDestinations = destinations(before); var newDestinations = destinations(next);
        var oldSubscriptions = subscriptions(before); var newSubscriptions = subscriptions(next);
        // Remove children first, freeing active uniqueness keys within this same transaction.
        synchronize(session, SUBSCRIPTIONS, oldSubscriptions, newSubscriptions, change, now);
        synchronize(session, DESTINATIONS, oldDestinations, newDestinations, change, now);
    }
    private void synchronize(ClientSession session, String collection, Map<String, Document> before,
                             Map<String, Document> after, ConfigurationChange change, Instant now) {
        for (var entry : before.entrySet()) {
            if (after.containsKey(entry.getKey())) continue;
            Document old = entry.getValue();
            var tombstone = AlertDocuments.base(StableIdentity.hash("alert-config-tombstone-v1", collection, entry.getKey()))
                    .append("kind", collection).append("removedId", entry.getKey()).append("guildId", change.guildId())
                    .append("channelId", old.getString("channelId")).append("incarnation", old.getString("incarnation"))
                    .append("actionId", change.actionId()).append("removedAt", AlertDocuments.date(now));
            db.collection(TOMBSTONES).insertOne(session, tombstone);
            if (db.collection(collection).deleteOne(session, fence(old), new DeleteOptions().collation(SIMPLE)).getDeletedCount() != 1)
                throw new IllegalStateException("Configuration projection missing or changed.");
        }
        for (var entry : after.entrySet()) {
            var old = before.get(entry.getKey()); var next = entry.getValue();
            if (old == null) db.collection(collection).insertOne(session, next);
            else if (!old.equals(next)) {
                var fields = new Document(next); fields.remove("_id");
                if (db.collection(collection).updateOne(session, fence(old), new Document("$set", fields),
                        new UpdateOptions().collation(SIMPLE)).getMatchedCount() != 1)
                    throw new IllegalStateException("Configuration projection missing or changed.");
            }
        }
    }
    private static org.bson.conversions.Bson fence(Document old) {
        return and(eq("_id", old.getString("_id")), eq("guildId", old.getString("guildId")), eq("schemaVersion", 1),
                eq("incarnation", old.getString("incarnation")), eq("revision", AlertDocuments.number(old, "revision")));
    }
    private static Map<String, Document> destinations(GuildConfiguration configuration) {
        Map<String, Document> result = new LinkedHashMap<>();
        for (var d : configuration.destinations()) result.put(d.ref().destinationId(), ConfigurationDocuments.destination(d));
        return result;
    }
    private static Map<String, Document> subscriptions(GuildConfiguration configuration) {
        Map<String, Document> result = new LinkedHashMap<>();
        for (var d : configuration.destinations()) for (var s : d.subscriptions())
            result.put(s.id(), AlertDocuments.subscription(d.projection(s, configuration.enabled())));
        return result;
    }
}
