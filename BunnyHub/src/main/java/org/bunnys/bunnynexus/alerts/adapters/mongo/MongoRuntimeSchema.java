package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.bunnys.bunnynexus.alerts.application.OwnershipRepository;
import org.bunnys.bunnynexus.alerts.domain.StableIdentity;
import java.util.*;
import static com.mongodb.client.model.Filters.eq;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertSchema.*;

/** Additive ownership and scheduling indexes; explicit provisioning only, with alert writers stopped. */
public final class MongoRuntimeSchema {
    public static final String OWNERSHIP = "AlertRuntimeOwnership", MIGRATION = "alert-runtime-v1";
    private final MongoAlertDatabase db;
    public MongoRuntimeSchema(MongoAlertDatabase db) { this.db = Objects.requireNonNull(db); }
    public static List<IndexSpec> indexes() {
        return List.of(new IndexSpec(OWNERSHIP, "ownership_expiry", List.of("leaseUntil", "_id"), false, false),
                new IndexSpec(MongoCatalogSchema.SOURCES, "source_ownership_expiry", List.of("leaseUntil", "_id"), false, false));
    }
    public static String checksum() {
        var parts = new ArrayList<>(List.of(MIGRATION, "singleton-egress", "explicit-source-scopes"));
        for (var s : indexes()) parts.addAll(List.of(s.collection(), s.name(), String.join(",", s.fields())));
        return StableIdentity.hash(parts.toArray(String[]::new));
    }
    public void install() {
        new MongoAlertSchema(db).verifyInstalled(); new MongoCatalogSchema(db).verifyInstalled();
        if (db.collection(SCHEMA).find(eq("_id", MIGRATION)).collation(SIMPLE).first() != null) { verifyInstalled(); return; }
        for (var index : indexes()) db.collection(index.collection()).createIndex(index.keys(), index.options());
        provision(new OwnershipRepository.Runtime());
        db.collection(SCHEMA).updateOne(eq("_id", MIGRATION), new Document("$setOnInsert",
                new Document("schemaVersion", 1).append("checksum", checksum())).append("$currentDate", new Document("appliedAt", true)),
                new UpdateOptions().upsert(true).collation(SIMPLE));
        verifyInstalled();
    }
    public void provisionSources(Set<OwnershipRepository.Source> sources) {
        if (sources.size() > 256) throw new IllegalArgumentException("Bound source scope provisioning.");
        var snapshot = Set.copyOf(sources); verifyInstalled();
        for (var source : snapshot) provision(source);
    }
    private void provision(OwnershipRepository.Resource resource) {
        var fields = new Document("schemaVersion", 1).append("revision", 1L).append("generation", 0L).append("leaseUntil", new Date(0));
        if (resource instanceof OwnershipRepository.Source source) fields.append("sourceId", source.sourceId().value()).append("scope", source.scope());
        db.collection(MongoOwnershipRepository.collection(resource)).updateOne(eq("_id", MongoOwnershipRepository.id(resource)),
                new Document("$setOnInsert", fields), new UpdateOptions().upsert(true).collation(SIMPLE));
    }
    public void verifyInstalled() {
        var marker = db.collection(SCHEMA).find(eq("_id", MIGRATION)).collation(SIMPLE).first();
        if (marker == null || !checksum().equals(marker.getString("checksum"))) throw new IllegalStateException("Runtime schema not installed.");
        AlertDocuments.version(marker); new MongoIndexVerifier(db).verify(indexes());
        var owner = db.collection(OWNERSHIP).find(eq("_id", "egress")).collation(SIMPLE).first(); AlertDocuments.version(owner);
        if (AlertDocuments.number(owner, "generation") < 0 || AlertDocuments.number(owner, "revision") < 1)
            throw new IllegalStateException("Invalid runtime ownership counters.");
        AlertDocuments.instant(owner, "leaseUntil");
    }
}
