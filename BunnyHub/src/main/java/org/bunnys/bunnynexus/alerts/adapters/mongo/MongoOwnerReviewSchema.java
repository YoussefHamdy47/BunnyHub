package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.bunnys.bunnynexus.alerts.domain.StableIdentity;
import java.util.*;
import static com.mongodb.client.model.Filters.eq;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertSchema.*;

/** Explicit additive owner gate migration. All old send writers must be stopped before install. */
public final class MongoOwnerReviewSchema {
    public static final String REVIEWS = "AlertOwnerReviews", AUDIT = "AlertOwnerReviewAudit", MIGRATION = "alert-owner-review-v1";
    private final MongoAlertDatabase db;
    public MongoOwnerReviewSchema(MongoAlertDatabase db) { this.db = Objects.requireNonNull(db); }
    public static List<IndexSpec> indexes() {
        return List.of(new IndexSpec(REVIEWS, "owner_review_state", List.of("ownerId", "state", "_id"), false, false),
                new IndexSpec(AUDIT, "owner_review_audit", List.of("reviewId", "revision"), true, false));
    }
    public static String checksum() {
        var parts = new ArrayList<>(List.of(MIGRATION, "owner-two-step-v1", "send-attempt-cap-v1", "template-pinned-v1"));
        for (var i : indexes()) { parts.add(i.collection()); parts.add(i.name()); parts.add(String.join(",", i.fields())); parts.add(Boolean.toString(i.unique())); }
        return StableIdentity.hash(parts.toArray(String[]::new));
    }
    public void install() {
        new MongoCatalogSchema(db).verifyInstalled();
        if (db.collection(SCHEMA).find(eq("_id", MIGRATION)).collation(SIMPLE).first() != null) { verifyInstalled(); return; }
        for (var i : indexes()) db.collection(i.collection()).createIndex(i.keys(), i.options());
        db.collection(SCHEMA).updateOne(eq("_id", MIGRATION), new Document("$setOnInsert", new Document("schemaVersion", 1)
                .append("checksum", checksum())).append("$currentDate", new Document("appliedAt", true)), new UpdateOptions().upsert(true).collation(SIMPLE));
        verifyInstalled();
    }
    public void verifyInstalled() {
        var marker = db.collection(SCHEMA).find(eq("_id", MIGRATION)).collation(SIMPLE).first();
        AlertDocuments.version(marker);
        if (!checksum().equals(marker.getString("checksum"))) throw new IllegalStateException("Owner review migration mismatch.");
        new MongoIndexVerifier(db).verify(indexes());
    }
}
