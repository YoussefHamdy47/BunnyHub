package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import java.util.*;
import static com.mongodb.client.model.Filters.eq;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertSchema.*;

/** Explicit additive migration for a fresh configuration store, with all alert writers stopped. */
public final class MongoConfigurationSchema {
    public static final String CONFIGURATIONS = "AlertConfigurations", AUDIT = "AlertAudit", TOMBSTONES = "AlertConfigTombstones";
    public static final String MIGRATION = "alert-configuration-v1";
    private final MongoAlertDatabase db;
    public MongoConfigurationSchema(MongoAlertDatabase db) { this.db = Objects.requireNonNull(db); }
    public static List<IndexSpec> indexes() {
        return List.of(new IndexSpec(CONFIGURATIONS, "configuration_status", List.of("deleted", "enabled", "_id"), false, false),
                new IndexSpec(AUDIT, "guild_config_history", List.of("guildId", "revision"), true, false),
                new IndexSpec(TOMBSTONES, "guild_removed_identities", List.of("guildId", "removedAt", "_id"), false, false));
    }
    public static String checksum() {
        var parts = new ArrayList<>(List.of(MIGRATION, "aggregate-guard-format-1", "retain-audit-and-tombstones"));
        for (var index : indexes()) parts.addAll(List.of(index.collection(), index.name(), String.join(",", index.fields()), Boolean.toString(index.unique())));
        return org.bunnys.bunnynexus.alerts.domain.StableIdentity.hash(parts.toArray(String[]::new));
    }
    public void install() {
        new MongoAlertSchema(db).verifyInstalled();
        if (db.collection(SCHEMA).find(eq("_id", MIGRATION)).collation(SIMPLE).first() != null) { verifyInstalled(); return; }
        // Previous foundation had no configuration writer. Never invent aggregates over manually seeded state.
        for (String collection : List.of(GUILDS, DESTINATIONS, SUBSCRIPTIONS, CONFIGURATIONS, AUDIT, TOMBSTONES))
            if (db.collection(collection).find().projection(new Document("_id", 1)).first() != null)
                throw new IllegalStateException("Existing configuration requires an explicit import/reconciliation migration.");
        for (var index : indexes()) db.collection(index.collection()).createIndex(index.keys(), index.options());
        db.collection(SCHEMA).updateOne(eq("_id", MIGRATION), new Document("$setOnInsert",
                new Document("schemaVersion", 1).append("checksum", checksum())).append("$currentDate", new Document("appliedAt", true)),
                new UpdateOptions().upsert(true).collation(SIMPLE));
        verifyInstalled();
    }
    public void verifyInstalled() {
        var marker = db.collection(SCHEMA).find(eq("_id", MIGRATION)).collation(SIMPLE).first();
        if (marker == null || !checksum().equals(marker.getString("checksum"))) throw new IllegalStateException("Configuration schema not installed.");
        AlertDocuments.version(marker);
        new MongoIndexVerifier(db).verify(indexes());
    }
}
