package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.client.model.*;
import org.bson.Document;
import java.util.*;
import static com.mongodb.client.model.Filters.eq;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertSchema.*;

/** Additive second migration; leaves the original base-v1 checksum and data untouched. */
public final class MongoCatalogSchema {
    public static final String OFFERS = "AlertOffers", EVENTS = "AlertEvents", SOURCES = "AlertSourceState", MAPPINGS = "AlertSourceMappings";
    public static final String MIGRATION = "alert-catalog-v1";
    private final MongoAlertDatabase db;
    public MongoCatalogSchema(MongoAlertDatabase db) { this.db = Objects.requireNonNull(db); }
    public static List<IndexSpec> indexes() {
        return List.of(new IndexSpec(OFFERS, "canonical_offer_unique", List.of("storeId", "campaignKey", "market"), true, false),
                new IndexSpec(EVENTS, "offer_event_unique", List.of("offerId", "kind"), true, false),
                new IndexSpec(SOURCES, "source_scope_unique", List.of("sourceId", "scope"), true, false),
                new IndexSpec(MAPPINGS, "source_occurrence_unique", List.of("sourceId", "scope", "sourceItemId", "offerId"), true, false));
    }
    public static String checksum() {
        List<String> parts = new ArrayList<>(List.of(MIGRATION, "outbox-counter-v1"));
        for (var spec : indexes()) { parts.add(spec.collection()); parts.add(spec.name()); parts.add(String.join(",", spec.fields())); parts.add(Boolean.toString(spec.unique())); }
        return CatalogDocuments.id(parts.toArray(String[]::new));
    }
    public void install(long maximumPendingPlans) {
        if (maximumPendingPlans < 1) throw new IllegalArgumentException("Outbox capacity must be positive.");
        new MongoAlertSchema(db).verifyInstalled();
        var marker = db.collection(SCHEMA).find(eq("_id", MIGRATION)).first();
        if (marker != null) { verifyInstalled(); return; }
        if (db.collection(BACKLOG).find(eq("_id", "outbox")).first() == null
                && db.collection(PLANS).find(eq("outboxCounted", true)).projection(new Document("_id", 1)).first() != null)
            throw new IllegalStateException("Existing counted plans require counter reconciliation.");
        for (var index : indexes()) db.collection(index.collection()).createIndex(index.keys(), index.options());
        db.collection(BACKLOG).updateOne(eq("_id", "outbox"), new Document("$setOnInsert", new Document("schemaVersion", 1)
                .append("revision", 1L).append("pendingPlans", 0L).append("maxPlans", maximumPendingPlans).append("paused", false)), new UpdateOptions().upsert(true));
        db.collection(SCHEMA).updateOne(eq("_id", MIGRATION), new Document("$setOnInsert", new Document("schemaVersion", 1)
                .append("checksum", checksum())).append("$currentDate", new Document("appliedAt", true)), new UpdateOptions().upsert(true));
        verifyInstalled();
    }
    public void verifyInstalled() {
        var marker = db.collection(SCHEMA).find(eq("_id", MIGRATION)).first();
        if (marker == null || !checksum().equals(marker.getString("checksum"))) throw new IllegalStateException("Catalog schema not installed.");
        AlertDocuments.version(marker);
        new MongoIndexVerifier(db).verify(indexes());
        var control = db.collection(BACKLOG).find(eq("_id", "outbox")).first(); AlertDocuments.version(control);
        if (AlertDocuments.number(control, "pendingPlans") < 0 || AlertDocuments.number(control, "maxPlans") < 1
                || !(control.get("paused") instanceof Boolean)) throw new IllegalStateException("Invalid outbox guard.");
    }
}
