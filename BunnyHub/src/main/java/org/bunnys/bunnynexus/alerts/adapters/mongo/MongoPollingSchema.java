package org.bunnys.bunnynexus.alerts.adapters.mongo;

import java.util.*;
import org.bson.Document;
import com.mongodb.client.model.UpdateOptions;
import org.bunnys.bunnynexus.alerts.application.OwnershipRepository;
import org.bunnys.bunnynexus.alerts.domain.StableIdentity;
import static com.mongodb.client.model.Filters.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertSchema.*;

/** Explicit additive installer with all source writers stopped. No constructors perform migration. */
public final class MongoPollingSchema {
    public static final String MIGRATION = "alert-polling-v1";
    private final MongoAlertDatabase db;
    public MongoPollingSchema(MongoAlertDatabase db) { this.db = Objects.requireNonNull(db); }
    public static String checksum() { return StableIdentity.hash(MIGRATION, "source-record-poll-format-1", "shadow-summary-only"); }
    public void install() {
        new MongoRuntimeSchema(db).verifyInstalled();
        if (db.collection(SCHEMA).find(eq("_id", MIGRATION)).collation(SIMPLE).first() != null) { verifyInstalled(); return; }
        db.collection(SCHEMA).updateOne(eq("_id", MIGRATION), new Document("$setOnInsert",
                new Document("schemaVersion", 1).append("checksum", checksum())).append("$currentDate", new Document("appliedAt", true)),
                new UpdateOptions().upsert(true).collation(SIMPLE));
        verifyInstalled();
    }
    public void verifyInstalled() {
        var marker = db.collection(SCHEMA).find(eq("_id", MIGRATION)).collation(SIMPLE).first();
        AlertDocuments.version(marker);
        if (!checksum().equals(marker.getString("checksum"))) throw new IllegalStateException("Polling schema mismatch.");
    }
    public void provision(Set<OwnershipRepository.Source> sources) {
        var snapshot = Set.copyOf(sources);
        if (snapshot.size() > 256) throw new IllegalArgumentException("Too many source scopes.");
        verifyInstalled();
        for (var source : snapshot) {
            var collection = db.collection(MongoCatalogSchema.SOURCES);
            var existing = collection.find(MongoOwnershipRepository.identity(source)).collation(SIMPLE).first();
            AlertDocuments.version(existing);
            if (!existing.containsKey("pollFormat") && existing.keySet().stream()
                    .anyMatch(key -> key.startsWith("poll") || key.equals("nextPollAt")))
                throw new IllegalStateException("Unversioned polling state requires explicit reconciliation.");
            collection.updateOne(and(MongoOwnershipRepository.identity(source), exists("pollFormat", false)),
                    new Document("$set", new Document("pollFormat", 1).append("pollPaused", false)
                            .append("pollFailures", 0L).append("nextPollAt", new Date(0))), new UpdateOptions().collation(SIMPLE));
            var record = collection.find(MongoOwnershipRepository.identity(source)).collation(SIMPLE).first();
            AlertDocuments.version(record);
            if (AlertDocuments.number(record, "pollFormat") != 1 || !(record.get("pollPaused") instanceof Boolean)
                    || AlertDocuments.number(record, "pollFailures") < 0 || AlertDocuments.number(record, "pollFailures") > 6)
                throw new IllegalStateException("Invalid polling state.");
            AlertDocuments.instant(record, "nextPollAt");
        }
    }
}
