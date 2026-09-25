package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.client.ClientSession;
import com.mongodb.client.model.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bunnys.bunnynexus.alerts.application.DeliveryQueue;
import org.bunnys.bunnynexus.alerts.domain.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static com.mongodb.client.model.Filters.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertDatabase.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertSchema.*;

/** Fenced database queue operations. No method here authorizes or performs an external send. */
public final class MongoDeliveryQueue implements DeliveryQueue {
    private final MongoAlertDatabase db;
    public MongoDeliveryQueue(MongoAlertDatabase db) { this.db = Objects.requireNonNull(db); }
    @Override public List<DeliveryJob> due(DeliveryJob.State state, String guildId, int limit) {
        if (state != DeliveryJob.State.READY && state != DeliveryJob.State.RETRY_WAIT)
            throw new IllegalArgumentException("Select READY and RETRY_WAIT separately.");
        FanoutPlan.pageSize(limit); AlertIdentity.snowflake(guildId);
        Bson filter = and(eq("guildId", guildId), eq("state", state.name()), eq("schemaVersion", 1), dueNow());
        List<DeliveryJob> result = new ArrayList<>();
        try (var cursor = db.collection(DELIVERIES).find(filter).sort(new Document("dueAt", 1).append("_id", 1))
                .collation(SIMPLE).limit(limit).batchSize(limit).iterator()) {
            while (cursor.hasNext()) {
                if (result.size() >= limit) throw new IllegalStateException("Oversized due page.");
                result.add(AlertDocuments.delivery(cursor.next()));
            }
        }
        return List.copyOf(result);
    }
    @Override public Optional<DeliveryJob> claim(String id, String token, Duration duration) {
        AlertIdentity.token(id); AlertIdentity.token(token); long millis = positiveMillis(duration);
        Document fields = new Document("state", "LEASED").append("leaseToken", literal(token))
                .append("generation", increment("generation")).append("revision", increment("revision"))
                .append("leaseUntil", new Document("$add", List.of("$$NOW", millis))).append("reason", "CLAIMED");
        Bson filter = and(eq("_id", id), eq("schemaVersion", 1), in("state", "READY", "RETRY_WAIT"), dueNow());
        Document result = db.collection(DELIVERIES).findOneAndUpdate(filter, List.of(new Document("$set", fields)),
                new FindOneAndUpdateOptions().collation(SIMPLE).returnDocument(ReturnDocument.AFTER));
        return Optional.ofNullable(result).map(AlertDocuments::delivery);
    }
    @Override public Optional<DeliveryJob> recoverExpired(String id, long revision) {
        validate(id, revision);
        Bson guard = and(eq("_id", id), eq("schemaVersion", 1), eq("revision", revision),
                in("state", "LEASED", "SENDING"), expiredLease());
        try {
            return db.transaction(session -> {
                Document d = touch(session, guard);
                if (d == null) return Optional.empty();
                DeliveryJob old = AlertDocuments.delivery(d);
                DeliveryJob next = old.recoverExpired(AlertDocuments.instant(d, "checkedAt"));
                persist(session, old, next, guard);
                return Optional.of(next);
            });
        } catch (LostLease conflict) { return Optional.empty(); }
    }
    @Override public Optional<DeliveryJob> recordOutcome(String id, long revision, DeliveryJob.Lease owner,
            String attemptId, DeliveryJob.Outcome outcome, Optional<Instant> retryAt) {
        validate(id, revision); Objects.requireNonNull(owner); AlertIdentity.token(attemptId);
        Objects.requireNonNull(outcome); Objects.requireNonNull(retryAt); retryAt.ifPresent(AlertDocuments::date);
        Bson guard = and(eq("_id", id), eq("schemaVersion", 1), eq("revision", revision), eq("state", "SENDING"),
                eq("leaseToken", owner.token()), eq("generation", owner.generation()), eq("attempt._id", attemptId), beforeLeaseExpiry());
        try {
            return db.transaction(session -> {
                Document d = touch(session, guard);
                if (d == null) return Optional.empty();
                DeliveryJob old = AlertDocuments.delivery(d);
                DeliveryJob next = old.complete(owner, attemptId, AlertDocuments.instant(d, "checkedAt"), outcome, retryAt);
                persist(session, old, next, guard);
                return Optional.of(next);
            });
        } catch (LostLease conflict) { return Optional.empty(); }
    }
    private Document touch(ClientSession session, Bson guard) {
        return db.collection(DELIVERIES).findOneAndUpdate(session, guard,
                List.of(new Document("$set", new Document("checkedAt", "$$NOW"))),
                new FindOneAndUpdateOptions().collation(SIMPLE).returnDocument(ReturnDocument.AFTER));
    }
    private void persist(ClientSession session, DeliveryJob old, DeliveryJob next, Bson guard) {
        if (old.state() == DeliveryJob.State.SENDING) {
            var a = old.attempt().orElseThrow();
            Document changes = new Document("outcome", next.state().name()).append("reason", next.reason().name());
            next.messageId().ifPresent(message -> changes.append("messageId", message));
            // Receipt must already exist from atomic send authorization; a missing attempt is corruption, not an upsert.
            Bson attemptGuard = and(eq("_id", a.id()), eq("schemaVersion", 1), eq("jobId", old.id()),
                    eq("ordinal", a.ordinal()), eq("revision", 1L), eq("outcome", "SENDING"));
            if (db.collection(ATTEMPTS).updateOne(session, attemptGuard,
                    new Document("$set", changes).append("$inc", new Document("revision", 1L))
                            .append("$currentDate", new Document("finishedAt", true)), new UpdateOptions().collation(SIMPLE))
                    .getMatchedCount() != 1) throw new IllegalStateException("Missing or conflicting authorized attempt.");
        }
        if (next.state() == DeliveryJob.State.SENT || next.state() == DeliveryJob.State.FAILED || next.state() == DeliveryJob.State.SKIPPED) {
            if (db.collection(BACKLOG).updateOne(session,
                    and(eq("_id", "delivery"), eq("schemaVersion", 1), gte("pendingJobs", 1L)),
                    new Document("$inc", new Document("pendingJobs", -1L).append("revision", 1L)), new UpdateOptions().collation(SIMPLE)).getMatchedCount() != 1)
                throw new IllegalStateException("Alert backlog count is inconsistent.");
        }
        if (db.collection(DELIVERIES).replaceOne(session, guard, AlertDocuments.delivery(next), new ReplaceOptions().collation(SIMPLE)).getMatchedCount() != 1)
            throw new LostLease();
    }
    private static Bson dueNow() { return new Document("$expr", new Document("$lte", List.of("$dueAt", "$$NOW"))); }
    private static void validate(String id, long revision) {
        AlertIdentity.token(id);
        if (revision < 1) throw new IllegalArgumentException("Revision must be positive.");
    }
    private static final class LostLease extends RuntimeException {}
}
