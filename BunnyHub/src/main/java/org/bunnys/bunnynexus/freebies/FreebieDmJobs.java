package org.bunnys.bunnynexus.freebies;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/**
 * Durable, leased queue of personal game DMs ({@code FreebieDmJobs}). {@code _id} is {@code <offerId>|<userId>},
 * so planning is an idempotent insert: one DM per offer and user however often fan-out runs. Every state change is
 * a single-document conditional update; outcomes need the current lease token and an unexpired lease.
 * States: {@code PENDING}/{@code SENDING} are open; {@code SENT}, {@code SKIPPED}, {@code FAILED},
 * {@code CANCELLED} are final.
 */
final class FreebieDmJobs {
    static final String COLLECTION = "FreebieDmJobs";
    static final List<String> OPEN = List.of("PENDING", "SENDING"), FINAL = List.of("SENT", "SKIPPED", "FAILED", "CANCELLED");
    private static final int INSERT_BATCH = 500;

    record Job(String id, String offerId, String userId, int attempts, boolean verifyFirst, boolean counted,
               String channelId, String token) {}
    record Stats(long pending, long failed, long skipped, Instant oldest) {}
    record Totals(long sent, long skipped, long failed, long cancelled, long open) {}

    private final MongoCollection<Document> jobs, lifecycleJobs, offers;

    FreebieDmJobs(MongoDatabase db) {
        jobs = db.getCollection(COLLECTION);
        lifecycleJobs = db.getCollection(FreebieLifecycleStore.COLLECTION);
        offers = db.getCollection(FreebieRepository.OFFERS);
    }

    void installIndexes() {
        jobs.createIndex(Indexes.ascending("state", "nextAttemptAt"), new IndexOptions().name("dm_due"));
        jobs.createIndex(Indexes.ascending("state", "leaseUntil"), new IndexOptions().name("dm_lease"));
        jobs.createIndex(Indexes.ascending("offerId", "state"), new IndexOptions().name("dm_offer"));
        jobs.createIndex(Indexes.ascending("userId", "state"), new IndexOptions().name("dm_user"));
        jobs.createIndex(Indexes.ascending("state", "finishedAt"), new IndexOptions().name("dm_finished"));
    }

    /** One job per subscriber for this offer; returns how many were new. Safe to run concurrently and repeatedly. */
    int plan(String offerId, List<String> userIds, Instant now) {
        int planned = 0;
        List<Document> batch = new ArrayList<>();
        for (String user : userIds) {
            batch.add(new Document("_id", FreebieDm.jobId(offerId, user)).append("schemaVersion", 1).append("offerId", offerId)
                    .append("userId", user).append("state", "PENDING").append("attempts", 0).append("verifyFirst", false)
                    .append("nextAttemptAt", Date.from(now)).append("createdAt", Date.from(now)));
            if (batch.size() == INSERT_BATCH) { planned += insert(batch); batch.clear(); }
        }
        return planned + insert(batch);
    }

    private int insert(List<Document> batch) {
        if (batch.isEmpty()) return 0;
        try {
            return jobs.insertMany(batch, new InsertManyOptions().ordered(false)).getInsertedIds().size();
        } catch (MongoBulkWriteException partial) {
            if (partial.getWriteErrors().stream().anyMatch(e -> ErrorCategory.fromErrorCode(e.getCode()) != ErrorCategory.DUPLICATE_KEY))
                throw partial;
            return batch.size() - partial.getWriteErrors().size();
        }
    }

    /** The offer was stopped or ended: DMs not yet started never go out. A leased one re-checks before sending. */
    long cancelForOffer(String offerId, Instant now) {
        return jobs.updateMany(and(eq("offerId", offerId), eq("state", "PENDING")), cancel(FreebieDm.Cancel.OFFER_ENDED, now)).getModifiedCount();
    }

    /** The user can no longer be messaged (or unsubscribed): their queued DMs end. */
    long cancelForUser(String userId, FreebieDm.Cancel reason, Instant now) {
        return jobs.updateMany(and(eq("userId", userId), eq("state", "PENDING")), cancel(reason, now)).getModifiedCount();
    }

    private static Bson cancel(FreebieDm.Cancel reason, Instant now) {
        return combine(set("state", "CANCELLED"), set("cancelReason", reason.name()), set("finishedAt", Date.from(now)));
    }

    // ------------------------------------------------------------------ leases

    /** A worker that died mid-send leaves SENDING behind; the next attempt checks the DM history first. */
    long recover(Instant now) {
        return jobs.updateMany(and(eq("state", "SENDING"), lte("leaseUntil", Date.from(now))),
                combine(set("state", "PENDING"), set("verifyFirst", true), set("nextAttemptAt", Date.from(now)),
                        unset("leaseToken"), unset("leaseUntil"))).getModifiedCount();
    }

    Optional<Job> claim(Instant now, Duration lease) {
        String token = UUID.randomUUID().toString();
        var d = jobs.findOneAndUpdate(and(eq("state", "PENDING"), lte("nextAttemptAt", Date.from(now))),
                combine(set("state", "SENDING"), set("leaseToken", token), set("leaseUntil", Date.from(now.plus(lease))),
                        inc("attempts", 1), set("lastAttemptAt", Date.from(now))),
                new FindOneAndUpdateOptions().sort(Sorts.ascending("nextAttemptAt", "_id")).returnDocument(ReturnDocument.AFTER));
        if (d == null) return Optional.empty();
        return Optional.of(new Job(d.getString("_id"), d.getString("offerId"), d.getString("userId"), d.getInteger("attempts", 1),
                d.getBoolean("verifyFirst", false), d.getBoolean("counted", false), d.getString("channelId"), token));
    }

    static Bson owned(Job job, Instant now) {
        return and(eq("_id", job.id()), eq("state", "SENDING"), eq("leaseToken", job.token()), gt("leaseUntil", Date.from(now)));
    }

    boolean owns(Job job, Instant now) { return jobs.countDocuments(owned(job, now)) == 1; }

    /** Recorded once, after the daily counter accepted this DM, so a retry never counts it twice. */
    boolean counted(Job job, Instant now) {
        return jobs.updateOne(owned(job, now), set("counted", true)).getModifiedCount() == 1;
    }

    /** Persist the DM channel and the uncertainty before submitting, so a crash after acceptance checks history. */
    boolean submitting(Job job, String channelId, Instant now) {
        return jobs.updateOne(owned(job, now), combine(set("verifyFirst", true), set("channelId", channelId),
                set("submittedAt", Date.from(now)))).getModifiedCount() == 1;
    }

    private boolean finish(Job job, Instant now, Bson... changes) {
        var all = new ArrayList<>(List.of(changes));
        all.add(unset("leaseToken")); all.add(unset("leaseUntil"));
        return jobs.updateOne(owned(job, now), combine(all)).getModifiedCount() == 1;
    }

    boolean sent(Job job, String messageId, Instant now) {
        return finish(job, now, set("state", "SENT"), set("messageId", messageId), set("finishedAt", Date.from(now)), unset("failureDetail"));
    }

    boolean skipped(Job job, FreebieDm.Skip reason, Instant now) {
        return finish(job, now, set("state", "SKIPPED"), set("skipReason", reason.name()), set("finishedAt", Date.from(now)));
    }

    boolean cancelled(Job job, FreebieDm.Cancel reason, Instant now) {
        return finish(job, now, set("state", "CANCELLED"), set("cancelReason", reason.name()), set("finishedAt", Date.from(now)));
    }

    boolean retry(Job job, Instant next, boolean verifyFirst, String detail, Instant now) {
        return finish(job, now, set("state", "PENDING"), set("nextAttemptAt", Date.from(next)), set("verifyFirst", verifyFirst),
                set("failureDetail", truncate(detail)));
    }

    boolean failed(Job job, String detail, Instant now) {
        return finish(job, now, set("state", "FAILED"), set("failureDetail", truncate(detail)), set("finishedAt", Date.from(now)));
    }

    // ------------------------------------------------------------------ reads, retention

    Stats stats() {
        long pending = jobs.countDocuments(in("state", OPEN));
        var oldest = jobs.find(in("state", OPEN)).sort(Sorts.ascending("createdAt")).projection(Projections.include("createdAt")).first();
        return new Stats(pending, jobs.countDocuments(eq("state", "FAILED")), jobs.countDocuments(eq("state", "SKIPPED")),
                oldest == null ? null : oldest.getDate("createdAt").toInstant());
    }

    /** DM outcomes of one offer, for its completion summary (which never waits for them). */
    Totals totals(String offerId) {
        Map<String, Long> by = new HashMap<>();
        for (Document d : jobs.aggregate(List.of(Aggregates.match(eq("offerId", offerId)),
                Aggregates.group("$state", Accumulators.sum("n", 1L)))))
            by.put(d.getString("_id"), ((Number) d.get("n")).longValue());
        return new Totals(by.getOrDefault("SENT", 0L), by.getOrDefault("SKIPPED", 0L), by.getOrDefault("FAILED", 0L),
                by.getOrDefault("CANCELLED", 0L), by.getOrDefault("PENDING", 0L) + by.getOrDefault("SENDING", 0L));
    }

    boolean openFor(String offerId) { return jobs.countDocuments(and(eq("offerId", offerId), in("state", OPEN))) > 0; }

    /** Final DM jobs older than the retention window, oldest first, at most {@code limit}; open ones never. */
    long purge(Instant now, Instant cutoff, int limit) {
        Bson old = and(in("state", FINAL), lt("finishedAt", Date.from(cutoff)));
        long deleted = 0;
        for (var d : jobs.find(old).projection(Projections.include("_id", "offerId", "state")).sort(Sorts.ascending("finishedAt", "_id"))
                .limit(limit).into(new ArrayList<>())) {
            if (needsEndPlanning(d, now)) continue;
            if (lifecycleJobs.countDocuments(and(eq("deliveryId", "dm|" + d.getString("_id")),
                    in("state", FreebieLifecycleStore.OPEN))) > 0) continue;
            deleted += jobs.deleteOne(and(eq("_id", d.get("_id")), old)).getDeletedCount();
        }
        return deleted;
    }

    /** Live or recently ended cards must keep their destination until the lifecycle planner has seen them. */
    private boolean needsEndPlanning(Document delivery, Instant now) {
        if (!"SENT".equals(delivery.getString("state"))) return false;
        var offer = offers.find(eq("_id", delivery.getString("offerId"))).first();
        if (offer == null || offer.getBoolean("dmEndMarkDone", false)
                || !List.of("APPROVED", "COMPLETED", "STOPPED").contains(offer.getString("state"))) return false;
        Date end = offer.getDate("endsAt"), seen = offer.getDate("lastSeenAt"), updated = offer.getDate("updatedAt");
        boolean ended = offer.getBoolean("stoppedByOwner", false) || offer.getBoolean("gone", false)
                || (!offer.getBoolean("endsAtEstimated", false) && end != null && !end.toInstant().isAfter(now));
        Instant since = now.minus(FreebieLifecycleStore.BACKFILL);
        return !ended || (seen != null && !seen.toInstant().isBefore(since))
                || (updated != null && !updated.toInstant().isBefore(since));
    }

    private static String truncate(String text) {
        return text == null ? null : text.length() <= 300 ? text : text.substring(0, 300);
    }
}
