package org.bunnys.bunnynexus.alerts.adapters.mongo;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bunnys.bunnynexus.alerts.application.SchedulingRepository;
import org.bunnys.bunnynexus.alerts.domain.*;
import java.util.*;
import static com.mongodb.client.model.Filters.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertSchema.*;

public final class MongoSchedulingRepository implements SchedulingRepository {
    private final MongoAlertDatabase db;
    public MongoSchedulingRepository(MongoAlertDatabase db) { this.db = Objects.requireNonNull(db); }
    @Override public List<String> guilds(Optional<String> after, int limit) {
        FanoutPlan.pageSize(limit); after.ifPresent(AlertIdentity::snowflake);
        Bson filter = after.<Bson>map(id -> and(eq("schemaVersion", 1), gt("_id", id))).orElse(eq("schemaVersion", 1));
        List<String> result = new ArrayList<>();
        // Disabled/removed guilds still need their queued jobs resolved, so do not filter by enabled.
        try (var cursor = db.collection(GUILDS).find(filter).projection(new Document("_id", 1)).sort(new Document("_id", 1))
                .collation(SIMPLE).limit(limit).batchSize(limit).iterator()) {
            while (cursor.hasNext()) {
                if (result.size() >= limit) throw new IllegalStateException("Oversized guild page.");
                result.add(AlertIdentity.snowflake(AlertDocuments.string(cursor.next(), "_id")));
            }
        }
        return List.copyOf(result);
    }
    @Override public List<DeliveryJob> due(DeliveryJob.State state, Optional<String> guild, Optional<Cursor> after, int limit) {
        if (state != DeliveryJob.State.READY && state != DeliveryJob.State.RETRY_WAIT) throw new IllegalArgumentException("Separate due states required.");
        guild.ifPresent(AlertIdentity::snowflake);
        Bson filter = and(eq("schemaVersion", 1), eq("state", state.name()), new Document("$expr", new Document("$lte", List.of("$dueAt", "$$NOW"))));
        if (guild.isPresent()) filter = and(filter, eq("guildId", guild.get()));
        if (after.isPresent()) {
            var key = after.get(); var date = AlertDocuments.date(key.dueAt());
            filter = and(filter, or(gt("dueAt", date), and(eq("dueAt", date), gt("_id", key.jobId()))));
        }
        return jobs(filter, new Document("dueAt", 1).append("_id", 1), limit);
    }
    @Override public List<DeliveryJob> expired(DeliveryJob.State state, int limit) {
        if (state != DeliveryJob.State.LEASED && state != DeliveryJob.State.SENDING) throw new IllegalArgumentException("Separate leased states required.");
        return jobs(and(eq("schemaVersion", 1), eq("state", state.name()), MongoAlertDatabase.expiredLease()),
                new Document("leaseUntil", 1).append("_id", 1), limit);
    }
    private List<DeliveryJob> jobs(Bson filter, Document sort, int limit) {
        FanoutPlan.pageSize(limit); List<DeliveryJob> result = new ArrayList<>();
        try (var cursor = db.collection(DELIVERIES).find(filter).sort(sort).collation(SIMPLE).limit(limit).batchSize(limit).iterator()) {
            while (cursor.hasNext()) {
                if (result.size() >= limit) throw new IllegalStateException("Oversized scheduling page.");
                result.add(AlertDocuments.delivery(cursor.next()));
            }
        }
        return List.copyOf(result);
    }
}
