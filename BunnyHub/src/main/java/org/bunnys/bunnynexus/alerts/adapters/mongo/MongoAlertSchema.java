package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.client.model.*;
import org.bson.Document;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static com.mongodb.client.model.Filters.eq;

/** Explicit additive deployment step. Constructors and Main never run this migration. */
public final class MongoAlertSchema {
    public static final String DELIVERIES = "AlertDeliveries", ATTEMPTS = "AlertAttempts", PLANS = "AlertFanout",
            SUBSCRIPTIONS = "AlertSubscriptions", DESTINATIONS = "AlertDestinations", GUILDS = "AlertGuilds",
            BACKLOG = "AlertBacklog", SCHEMA = "AlertSchema";
    public static final String MIGRATION = "alert-base-v1";
    static final Collation SIMPLE = Collation.builder().locale("simple").build();
    public record IndexSpec(String collection, String name, List<String> fields, boolean unique, boolean automaticOnly) {
        public IndexSpec { fields = List.copyOf(fields); }
        public Document keys() {
            var keys = new Document(); fields.forEach(field -> keys.append(field, 1)); return keys;
        }
        public IndexOptions options() {
            var options = new IndexOptions().name(name).unique(unique).collation(SIMPLE);
            if (automaticOnly) options.partialFilterExpression(new Document("kind", "AUTOMATIC_FREE_GAME"));
            return options;
        }
    }
    public static List<IndexSpec> indexes() {
        return List.of(
                spec(DELIVERIES, "event_channel_unique", true, "eventId", "channelId"),
                spec(DELIVERIES, "due_jobs", false, "state", "dueAt", "_id"),
                spec(DELIVERIES, "guild_due_jobs", false, "guildId", "state", "dueAt", "_id"),
                spec(DELIVERIES, "expired_leases", false, "state", "leaseUntil", "_id"),
                spec(ATTEMPTS, "job_ordinal_unique", true, "jobId", "ordinal"),
                spec(ATTEMPTS, "job_history", false, "jobId", "startedAt"),
                new IndexSpec(PLANS, "automatic_event_unique", List.of("eventId"), true, true),
                spec(PLANS, "ready_plans", false, "state", "_id"),
                spec(PLANS, "expired_plan_leases", false, "state", "leaseUntil", "_id"),
                spec(SUBSCRIPTIONS, "destination_store_topic_unique", true, "destinationId", "storeId", "topic"),
                spec(SUBSCRIPTIONS, "automatic_audience", false, "storeId", "market", "topic", "enabled", "_id"),
                spec(DESTINATIONS, "channel_unique", true, "channelId"),
                spec(DESTINATIONS, "guild_destinations", false, "guildId", "enabled", "_id"),
                spec(GUILDS, "enabled_guilds", false, "enabled", "_id"));
    }
    private static IndexSpec spec(String collection, String name, boolean unique, String... fields) {
        return new IndexSpec(collection, name, List.of(fields), unique, false);
    }
    public static String checksum() {
        try {
            String manifest = MIGRATION + "|schema=1|backlog=pendingJobs,maxJobs,paused,revision|" + indexes();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifest.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private final MongoAlertDatabase db;
    public MongoAlertSchema(MongoAlertDatabase db) { this.db = Objects.requireNonNull(db); }

    /** Run while alert writers are stopped; an existing nonempty queue requires a separate counted migration. */
    public void install(long maximumOutstandingJobs) {
        if (maximumOutstandingJobs < 1) throw new IllegalArgumentException("Backlog capacity must be positive.");
        Document marker = db.collection(SCHEMA).find(eq("_id", MIGRATION)).first();
        if (marker != null) { verifyInstalled(); return; }
        Document backlog = db.collection(BACKLOG).find(eq("_id", "delivery")).first();
        if (backlog == null && db.collection(DELIVERIES).find().projection(new Document("_id", 1)).first() != null)
            throw new IllegalStateException("Cannot initialize an uncounted existing alert queue.");
        for (IndexSpec spec : indexes()) db.collection(spec.collection()).createIndex(spec.keys(), spec.options());
        // Referenced collections exist before future transactions; no user/timer collection is touched.
        db.collection(BACKLOG).updateOne(eq("_id", "delivery"), new Document("$setOnInsert",
                new Document("schemaVersion", 1).append("revision", 1L).append("pendingJobs", 0L)
                        .append("maxJobs", maximumOutstandingJobs).append("paused", false)), new UpdateOptions().upsert(true));
        db.collection(SCHEMA).updateOne(eq("_id", MIGRATION), new Document("$setOnInsert",
                new Document("schemaVersion", 1).append("checksum", checksum()))
                .append("$currentDate", new Document("appliedAt", true)), new UpdateOptions().upsert(true));
        verifyInstalled();
    }
    /** Bounded fail-closed check; never repair/drop an index automatically. Call before constructing workers. */
    public void verifyInstalled() {
        Document marker = db.collection(SCHEMA).find(eq("_id", MIGRATION)).first();
        if (marker == null || !checksum().equals(marker.getString("checksum")))
            throw new IllegalStateException("Alert schema migration is absent or incompatible.");
        AlertDocuments.version(marker);
        new MongoIndexVerifier(db).verify(indexes());
        Document backlog = db.collection(BACKLOG).find(eq("_id", "delivery")).first();
        AlertDocuments.version(backlog);
        if (AlertDocuments.number(backlog, "pendingJobs") < 0 || AlertDocuments.number(backlog, "maxJobs") < 1
                || !(backlog.get("paused") instanceof Boolean)) throw new IllegalStateException("Invalid alert backlog guard.");
    }
}
