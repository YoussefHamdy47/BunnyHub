package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.client.ClientSession;
import com.mongodb.client.model.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bunnys.bunnynexus.alerts.application.*;
import org.bunnys.bunnynexus.alerts.domain.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static com.mongodb.client.model.Filters.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertSchema.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertDatabase.*;

/** One bounded page per transaction; no scheduler, provider calls or Discord side effects. */
public final class MongoFanoutRepository implements FanoutRepository {
    private final MongoAlertDatabase db;
    private final FanoutEngine engine = new FanoutEngine();
    public MongoFanoutRepository(MongoAlertDatabase db) { this.db = Objects.requireNonNull(db); }

    @Override public Optional<FanoutPlan> claim(String planId, String token, Duration duration) {
        AlertIdentity.token(planId); AlertIdentity.token(token); long millis = positiveMillis(duration);
        // Direct keyed claim: no broad OR scan over the queue. Dispatch enumerates ready and expired separately.
        Bson filter = and(eq("_id", planId), eq("schemaVersion", 1), eq("kind", "AUTOMATIC_FREE_GAME"),
                new Document("$expr", new Document("$lte", List.of("$observedAt", "$$NOW"))),
                or(eq("state", "READY"), and(eq("state", "LEASED"), expiredLease())));
        Document update = new Document("state", "LEASED").append("revision", increment("revision"))
                .append("generation", increment("generation")).append("leaseToken", literal(token))
                .append("leaseUntil", new Document("$add", List.of("$$NOW", millis)));
        Document result = db.collection(PLANS).findOneAndUpdate(filter, List.of(new Document("$set", update)),
                new FindOneAndUpdateOptions().collation(SIMPLE).returnDocument(ReturnDocument.AFTER));
        return Optional.ofNullable(result).map(AlertDocuments::plan);
    }
    @Override public Result processPage(String planId, long expectedRevision, DeliveryJob.Lease owner, int size) {
        AlertIdentity.token(planId); Objects.requireNonNull(owner); FanoutPlan.pageSize(size);
        if (expectedRevision < 1) throw new IllegalArgumentException("Invalid revision.");
        try {
            return db.transaction(session -> process(session, planId, expectedRevision, owner, size));
        } catch (LostLease conflict) { return Result.CONFLICT; }
        // Duplicate-key/unknown-commit errors propagate: never swallow a transaction abort and advance a cursor.
    }
    private Result process(ClientSession session, String id, long revision, DeliveryJob.Lease owner, int size) {
        Bson guard = guard(id, revision, owner);
        Document raw = db.collection(PLANS).findOneAndUpdate(session, guard,
                List.of(new Document("$set", new Document("checkedAt", "$$NOW"))),
                new FindOneAndUpdateOptions().collation(SIMPLE).returnDocument(ReturnDocument.AFTER));
        if (raw == null) return Result.CONFLICT;
        FanoutPlan plan = AlertDocuments.plan(raw); Instant now = AlertDocuments.instant(raw, "checkedAt");
        var key = plan.notificationKey().offer();
        List<Bson> filters = new ArrayList<>(List.of(eq("storeId", key.store().value()), eq("market", key.market().value()),
                eq("topic", "FREE_GAME"), eq("enabled", true)));
        plan.afterSubscriptionId().ifPresent(after -> filters.add(gt("_id", after)));
        List<Document> rawSubscriptions = new ArrayList<>();
        try (var cursor = db.collection(SUBSCRIPTIONS).find(session, and(filters))
                .projection(Projections.include("_id", "schemaVersion", "guildId", "channelId", "destinationId", "incarnation",
                        "storeId", "market", "topic", "enabled", "enabledSince", "revision", "roleIds"))
                .collation(SIMPLE).sort(new Document("_id", 1)).limit(size).batchSize(size).iterator()) {
            while (cursor.hasNext()) {
                if (rawSubscriptions.size() >= size) throw new IllegalStateException("Mongo returned an oversized page.");
                Document sub = cursor.next();
                AlertDocuments.subscription(sub, null, null); // Validate each bounded row before retaining the next.
                rawSubscriptions.add(sub);
            }
        }
        Map<String, Document> guilds = guards(session, GUILDS, rawSubscriptions, "guildId");
        Map<String, Document> destinations = guards(session, DESTINATIONS, rawSubscriptions, "destinationId");
        List<Subscription> subscriptions = rawSubscriptions.stream().map(sub -> AlertDocuments.subscription(sub,
                guilds.get(AlertDocuments.string(sub, "guildId")), destinations.get(AlertDocuments.string(sub, "destinationId")))).toList();
        FanoutEngine.Page page = engine.plan(plan, owner, subscriptions, now);
        // Determine missing jobs inside the transaction. Unique indexes arbitrate concurrent different plans.
        List<DeliveryJob> missing = new ArrayList<>(); int duplicates = 0;
        Set<String> existingChannels = existingChannels(session, plan.eventId(), page.jobs());
        Set<AlertIdentity.DeliveryKey> seen = new HashSet<>();
        for (DeliveryJob job : page.jobs()) {
            if (!seen.add(job.key()) || existingChannels.contains(job.key().channelId()))
                duplicates++;
            else missing.add(job);
        }
        if (!missing.isEmpty()) {
            Bson capacity = and(eq("_id", "delivery"), eq("schemaVersion", 1), eq("paused", false),
                    new Document("$expr", new Document("$lte", List.of(
                            new Document("$add", List.of("$pendingJobs", missing.size())), "$maxJobs"))));
            var reserved = db.collection(BACKLOG).updateOne(session, capacity,
                    new Document("$inc", new Document("pendingJobs", (long) missing.size()).append("revision", 1L)), new UpdateOptions().collation(SIMPLE));
            if (reserved.getMatchedCount() != 1) return Result.BACKPRESSURE;
            db.collection(DELIVERIES).insertMany(session, missing.stream().map(AlertDocuments::delivery).toList());
        }
        FanoutPlan next = plan.finishPage(owner, now, page.lastSubscriptionId(), missing.size(), duplicates, page.skipped());
        if (next.state() == FanoutPlan.State.COMPLETE && plan.outboxCounted()) {
            if (db.collection(BACKLOG).updateOne(session, and(eq("_id", "outbox"), eq("schemaVersion", 1), gte("pendingPlans", 1L)),
                    new Document("$inc", new Document("pendingPlans", -1L).append("revision", 1L)), new UpdateOptions().collation(SIMPLE)).getMatchedCount() != 1)
                throw new IllegalStateException("Outbox count is inconsistent.");
        }
        // Fresh DB-time fence, after all work. Throw to abort inserted jobs/reservation if ownership expired.
        if (db.collection(PLANS).replaceOne(session, guard, AlertDocuments.plan(next), new ReplaceOptions().collation(SIMPLE)).getMatchedCount() != 1)
            throw new LostLease();
        return next.state() == FanoutPlan.State.COMPLETE ? Result.COMPLETE : Result.PAGE_COMMITTED;
    }
    private Map<String, Document> guards(ClientSession session, String collection, List<Document> subs, String reference) {
        if (subs.isEmpty()) return Map.of();
        Set<String> ids = new HashSet<>(); subs.forEach(sub -> ids.add(AlertDocuments.string(sub, reference)));
        Map<String, Document> result = new HashMap<>();
        try (var cursor = db.collection(collection).find(session, in("_id", ids))
                .projection(Projections.include("_id", "schemaVersion", "guildId", "channelId", "incarnation", "enabled", "revision"))
                .collation(SIMPLE).limit(ids.size()).batchSize(ids.size()).iterator()) {
            while (cursor.hasNext()) {
                if (result.size() >= ids.size()) throw new IllegalStateException("Oversized guard batch.");
                Document guard = cursor.next(); String id = AlertDocuments.string(guard, "_id");
                if (!ids.contains(id) || result.put(id, guard) != null) throw new IllegalStateException("Unexpected guard identity.");
            }
        }
        return result;
    }
    private Set<String> existingChannels(ClientSession session, String eventId, List<DeliveryJob> jobs) {
        if (jobs.isEmpty()) return Set.of();
        Set<String> channels = new HashSet<>(); jobs.forEach(job -> channels.add(job.key().channelId()));
        Set<String> result = new HashSet<>();
        try (var cursor = db.collection(DELIVERIES).find(session, and(eq("eventId", eventId), in("channelId", channels)))
                .collation(SIMPLE).projection(new Document("channelId", 1)).limit(channels.size()).batchSize(channels.size()).iterator()) {
            while (cursor.hasNext()) {
                if (result.size() >= channels.size()) throw new IllegalStateException("Oversized delivery identity batch.");
                String channel = AlertDocuments.string(cursor.next(), "channelId");
                if (!channels.contains(channel) || !result.add(channel)) throw new IllegalStateException("Duplicate/unexpected delivery identity.");
            }
        }
        return result;
    }
    static Bson guard(String id, long revision, DeliveryJob.Lease owner) {
        return and(eq("_id", id), eq("schemaVersion", 1), eq("kind", "AUTOMATIC_FREE_GAME"), eq("state", "LEASED"),
                eq("revision", revision), eq("leaseToken", owner.token()), eq("generation", owner.generation()), beforeLeaseExpiry());
    }
    private static final class LostLease extends RuntimeException {}
}
