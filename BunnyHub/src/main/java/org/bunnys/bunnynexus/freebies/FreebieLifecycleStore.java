package org.bunnys.bunnynexus.freebies;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bunnys.bunnynexus.freebies.FreebieLifecycle.Kind;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/**
 * Durable, leased queue for ended-card edits and ending-soon reminders ({@code FreebieLifecycleJobs}). The
 * {@code _id} is the uniqueness key: {@code edit|<deliveryId>} (one edit per posted alert) and
 * {@code remind|<offerId>|<channelId>} (one reminder per offer and channel), so planning is an idempotent insert
 * and concurrent planners cannot create a second job. Every state change is a single-document conditional
 * update; outcomes require the current lease token and an unexpired lease.
 */
final class FreebieLifecycleStore {
    static final String COLLECTION = "FreebieLifecycleJobs";
    static final List<String> OPEN = List.of("PENDING", "SENDING"), FINAL = List.of("DONE", "SKIPPED", "FAILED", "CANCELLED");
    private static final int PAGE = 500;

    record Job(String id, Kind kind, String offerId, String deliveryId, String guildId, String channelId, String messageId,
               boolean withdrawn, int hours, int attempts, boolean verifyFirst, String token, String recipientId) {
        Job(String id, Kind kind, String offerId, String deliveryId, String guildId, String channelId, String messageId,
            boolean withdrawn, int hours, int attempts, boolean verifyFirst, String token) {
            this(id, kind, offerId, deliveryId, guildId, channelId, messageId, withdrawn, hours, attempts, verifyFirst, token, null);
        }
    }
    record Planned(int queued, int withoutMessage) {}
    record Stats(long pending, long failed, Instant oldest) {}

    /** Offers ended or stopped longer ago than this when this feature first saw them are not edited. */
    static final Duration BACKFILL = Duration.ofDays(14);
    private final MongoCollection<Document> jobs, deliveries, offers, dmJobs;

    FreebieLifecycleStore(MongoDatabase db) {
        jobs = db.getCollection(COLLECTION);
        deliveries = db.getCollection(FreebieRepository.DELIVERIES);
        offers = db.getCollection(FreebieRepository.OFFERS);
        dmJobs = db.getCollection(FreebieDmJobs.COLLECTION);
    }

    // ------------------------------------------------------------------ offer scans

    record EndCandidate(FreebieOffer offer, boolean ownerStopped) {}

    /**
     * Posted offers whose cards are not yet marked and that look ended: stopped by the owner, gone from the feed,
     * or past a real end date. Recently active ones only, so old history is not rewritten in bulk.
     */
    List<EndCandidate> endCandidates(Instant now, int limit) {
        Date at = Date.from(now), since = Date.from(now.minus(BACKFILL));
        return offers.find(and(in("state", "APPROVED", "COMPLETED", "STOPPED"),
                        or(ne("endMarkDone", true), ne("dmEndMarkDone", true)),
                        or(eq("stoppedByOwner", true), eq("gone", true), and(lte("endsAt", at), ne("endsAtEstimated", true))),
                        or(gte("lastSeenAt", since), gte("updatedAt", since))))
                .sort(Sorts.ascending("updatedAt", "_id")).limit(limit)
                .map(d -> new EndCandidate(FreebieOffer.read(d), d.getBoolean("stoppedByOwner", false))).into(new ArrayList<>());
    }

    /** Recorded once no alert of the offer can still become sent, so later scans skip it. */
    void endMarked(String offerId, int withoutMessage, Instant now) {
        offers.updateOne(and(eq("_id", offerId), or(ne("endMarkDone", true), ne("dmEndMarkDone", true))),
                combine(set("endMarkDone", true), set("dmEndMarkDone", true),
                set("endMarkAt", Date.from(now)), set("endMarkSkipped", withoutMessage)));
    }

    /** Live approved offers whose real end is within {@code window}: the only ones a reminder can be due for. */
    List<FreebieOffer> endingWithin(Instant now, Duration window, int limit) {
        return offers.find(and(in("state", "APPROVED", "COMPLETED"), ne("gone", true), ne("stoppedByOwner", true),
                        ne("endsAtEstimated", true), gt("endsAt", Date.from(now)), lte("endsAt", Date.from(now.plus(window)))))
                .sort(Sorts.ascending("endsAt", "_id")).limit(limit).map(FreebieOffer::read).into(new ArrayList<>());
    }

    /** Sent alerts of {@code offerId} in the given channels, with their message ID and send time. */
    List<Document> sentIn(String offerId, Collection<String> channels) {
        return deliveries.find(and(eq("offerId", offerId), eq("state", "SENT"), in("channelId", channels)))
                .projection(Projections.include("_id", "guildId", "channelId", "messageId", "finishedAt", "state"))
                .into(new ArrayList<>());
    }

    long openDeliveries(String offerId) {
        return deliveries.countDocuments(and(eq("offerId", offerId), in("state", "PENDING", "SENDING", "BLOCKED")))
                + dmJobs.countDocuments(and(eq("offerId", offerId), in("state", FreebieDmJobs.OPEN)));
    }

    void installIndexes() {
        jobs.createIndex(Indexes.ascending("state", "dueAt"), new IndexOptions().name("lifecycle_due"));
        jobs.createIndex(Indexes.ascending("state", "kind", "dueAt"), new IndexOptions().name("lifecycle_kind_due"));
        jobs.createIndex(Indexes.ascending("state", "leaseUntil"), new IndexOptions().name("lifecycle_lease"));
        jobs.createIndex(Indexes.ascending("offerId", "kind", "state"), new IndexOptions().name("lifecycle_offer"));
        jobs.createIndex(Indexes.ascending("deliveryId", "state"), new IndexOptions().name("lifecycle_delivery"));
        jobs.createIndex(Indexes.ascending("state", "finishedAt"), new IndexOptions().name("lifecycle_finished"));
    }

    static String editId(String deliveryId) { return "edit|" + deliveryId; }
    static String remindId(String offerId, String channelId) { return "remind|" + offerId + "|" + channelId; }

    // ------------------------------------------------------------------ planning (idempotent)

    /**
     * One edit job per alert of this offer that was sent and has a saved message ID. Sent alerts without one
     * (historical rows) are counted, never retried. Safe to run concurrently and repeatedly.
     */
    Planned planEdits(String offerId, boolean withdrawn, Instant now) {
        var channels = planEdits(deliveries, offerId, withdrawn, now, false);
        var personal = planEdits(dmJobs, offerId, withdrawn, now, true);
        return new Planned(channels.queued() + personal.queued(), channels.withoutMessage() + personal.withoutMessage());
    }

    private Planned planEdits(MongoCollection<Document> source, String offerId, boolean withdrawn, Instant now, boolean dm) {
        int queued = 0, without = 0;
        String after = "";
        while (true) {
            var page = source.find(and(eq("offerId", offerId), eq("state", "SENT"), gt("_id", after)))
                    .projection(Projections.include("_id", "guildId", "channelId", "messageId", "userId"))
                    .sort(Sorts.ascending("_id")).limit(PAGE).into(new ArrayList<>());
            if (page.isEmpty()) break;
            List<Document> batch = new ArrayList<>();
            for (var d : page) {
                if (d.getString("messageId") == null || (dm && (d.getString("channelId") == null || d.getString("userId") == null))) {
                    without++; continue;
                }
                var destination = new Document(d);
                if (dm) destination.put("_id", "dm|" + d.getString("_id"));
                var edit = job(Kind.EDIT, editId(destination.getString("_id")), offerId, destination, now).append("withdrawn", withdrawn);
                if (dm) edit.append("recipientId", d.getString("userId"));
                batch.add(edit);
            }
            queued += insertIgnoringDuplicates(batch);
            after = page.getLast().getString("_id");
            if (page.size() < PAGE) break;
        }
        return new Planned(queued, without);
    }

    /** True only for the planner that created this channel's reminder. */
    boolean planReminder(String offerId, Document delivery, int hours, Instant now) {
        return insertIgnoringDuplicates(List.of(job(Kind.REMIND, remindId(offerId, delivery.getString("channelId")), offerId,
                delivery, now).append("hours", hours))) == 1;
    }

    private static Document job(Kind kind, String id, String offerId, Document delivery, Instant now) {
        return new Document("_id", id).append("schemaVersion", 1).append("kind", kind.name()).append("offerId", offerId)
                .append("deliveryId", delivery.getString("_id")).append("guildId", delivery.getString("guildId"))
                .append("channelId", delivery.getString("channelId")).append("messageId", delivery.getString("messageId"))
                .append("state", "PENDING").append("attempts", 0).append("verifyFirst", false)
                .append("dueAt", Date.from(now)).append("createdAt", Date.from(now));
    }

    private int insertIgnoringDuplicates(List<Document> batch) {
        if (batch.isEmpty()) return 0;
        try {
            return jobs.insertMany(batch, new InsertManyOptions().ordered(false)).getInsertedIds().size();
        } catch (MongoBulkWriteException partial) {
            if (partial.getWriteErrors().stream().anyMatch(e -> ErrorCategory.fromErrorCode(e.getCode()) != ErrorCategory.DUPLICATE_KEY))
                throw partial;
            return batch.size() - partial.getWriteErrors().size();
        }
    }

    /** The offer ended: reminders not yet started never post. A leased one re-checks the offer before sending. */
    long cancelReminders(String offerId, Instant now) {
        return jobs.updateMany(and(eq("offerId", offerId), eq("kind", "REMIND"), eq("state", "PENDING")),
                combine(set("state", "CANCELLED"), set("cancelReason", FreebieLifecycle.Cancel.OFFER_ENDED.name()),
                        set("finishedAt", Date.from(now)))).getModifiedCount();
    }

    // ------------------------------------------------------------------ leases

    /** A worker that died mid-job leaves SENDING behind; the next attempt must look before posting again. */
    long recover(Instant now) {
        return jobs.updateMany(and(eq("state", "SENDING"), lte("leaseUntil", Date.from(now))),
                combine(set("state", "PENDING"), set("verifyFirst", true), set("dueAt", Date.from(now)),
                        unset("leaseToken"), unset("leaseUntil"))).getModifiedCount();
    }

    /** The next due reminder, earliest due first; only when none is due, the next ended-card edit. */
    Optional<Job> claim(Instant now, Duration lease) {
        return claim(now, lease, Kind.REMIND).or(() -> claim(now, lease, Kind.EDIT));
    }

    Optional<Job> claim(Instant now, Duration lease, Kind kind) {
        String token = UUID.randomUUID().toString();
        var d = jobs.findOneAndUpdate(and(eq("state", "PENDING"), eq("kind", kind.name()), lte("dueAt", Date.from(now))),
                combine(set("state", "SENDING"), set("leaseToken", token), set("leaseUntil", Date.from(now.plus(lease))),
                        inc("attempts", 1), set("lastAttemptAt", Date.from(now))),
                new FindOneAndUpdateOptions().sort(Sorts.ascending("dueAt", "_id")).returnDocument(ReturnDocument.AFTER));
        if (d == null) return Optional.empty();
        return Optional.of(new Job(d.getString("_id"), Kind.valueOf(d.getString("kind")), d.getString("offerId"),
                d.getString("deliveryId"), d.getString("guildId"), d.getString("channelId"), d.getString("messageId"),
                d.getBoolean("withdrawn", false), d.getInteger("hours", 0), d.getInteger("attempts", 1),
                d.getBoolean("verifyFirst", false), token, d.getString("recipientId")));
    }

    static Bson owned(Job job, Instant now) {
        return and(eq("_id", job.id()), eq("state", "SENDING"), eq("leaseToken", job.token()), gt("leaseUntil", Date.from(now)));
    }

    boolean owns(Job job, Instant now) { return jobs.countDocuments(owned(job, now)) == 1; }

    /** Persist uncertainty before a reminder is submitted, so a crash after acceptance checks history first. */
    boolean submitting(Job job, Instant now) {
        return jobs.updateOne(owned(job, now), combine(set("verifyFirst", true), set("submittedAt", Date.from(now))))
                .getModifiedCount() == 1;
    }

    private boolean finish(Job job, Instant now, Bson... changes) {
        var all = new ArrayList<>(List.of(changes));
        all.add(unset("leaseToken")); all.add(unset("leaseUntil"));
        return jobs.updateOne(owned(job, now), combine(all)).getModifiedCount() == 1;
    }

    boolean done(Job job, String messageId, Instant now) {
        return finish(job, now, set("state", "DONE"), set("resultMessageId", messageId), set("finishedAt", Date.from(now)),
                unset("failureDetail"));
    }

    boolean skipped(Job job, FreebieLifecycle.Skip reason, Instant now) {
        return finish(job, now, set("state", "SKIPPED"), set("skipReason", reason.name()), set("finishedAt", Date.from(now)));
    }

    boolean cancelled(Job job, FreebieLifecycle.Cancel reason, Instant now) {
        return finish(job, now, set("state", "CANCELLED"), set("cancelReason", reason.name()), set("finishedAt", Date.from(now)));
    }

    boolean retry(Job job, Instant next, boolean verifyFirst, String detail, Instant now) {
        return finish(job, now, set("state", "PENDING"), set("dueAt", Date.from(next)), set("verifyFirst", verifyFirst),
                set("failureDetail", truncate(detail)));
    }

    boolean failed(Job job, String detail, Instant now) {
        return finish(job, now, set("state", "FAILED"), set("failureDetail", truncate(detail)), set("finishedAt", Date.from(now)));
    }

    /** Back in the queue without using an attempt (guild temporarily unavailable). */
    boolean release(Job job, Instant next, Instant now) {
        return finish(job, now, set("state", "PENDING"), set("dueAt", Date.from(next)), inc("attempts", -1));
    }

    // ------------------------------------------------------------------ reads, retention

    Stats stats(Kind kind, Instant now) {
        long pending = jobs.countDocuments(and(eq("kind", kind.name()), in("state", OPEN)));
        long failed = jobs.countDocuments(and(eq("kind", kind.name()), eq("state", "FAILED")));
        var oldest = jobs.find(and(eq("kind", kind.name()), in("state", OPEN))).sort(Sorts.ascending("createdAt"))
                .projection(Projections.include("createdAt")).first();
        return new Stats(pending, failed, oldest == null ? null : oldest.getDate("createdAt").toInstant());
    }

    /** The edit and reminder jobs of one destination, for its history audit page. */
    List<Document> forDestination(String offerId, String channelId) {
        return jobs.find(in("_id", editId(offerId + "|" + channelId), remindId(offerId, channelId)))
                .sort(Sorts.ascending("_id")).into(new ArrayList<>());
    }

    boolean openFor(String deliveryId) {
        return jobs.countDocuments(and(eq("deliveryId", deliveryId), in("state", OPEN))) > 0;
    }

    boolean openForOffer(String offerId) {
        return jobs.countDocuments(and(eq("offerId", offerId), in("state", OPEN))) > 0;
    }

    /** Final jobs older than the retention window, oldest first, at most {@code limit}. */
    long purge(Instant cutoff, int limit) {
        Bson old = and(in("state", FINAL), lt("finishedAt", Date.from(cutoff)));
        long deleted = 0;
        for (var d : jobs.find(old).projection(Projections.include("_id")).sort(Sorts.ascending("finishedAt", "_id"))
                .limit(limit).into(new ArrayList<>()))
            deleted += jobs.deleteOne(and(eq("_id", d.get("_id")), old)).getDeletedCount();
        return deleted;
    }

    static String explain(Document d) {
        String state = d.getString("state");
        try {
            if (d.getString("skipReason") != null) return FreebieLifecycle.Skip.valueOf(d.getString("skipReason")).text;
            if (d.getString("cancelReason") != null) return FreebieLifecycle.Cancel.valueOf(d.getString("cancelReason")).text;
        } catch (IllegalArgumentException unknown) { return "No reason recorded."; }
        return switch (state == null ? "" : state) {
            case "DONE" -> "Done.";
            case "FAILED" -> "Discord kept failing; attempts are used up.";
            case "PENDING", "SENDING" -> "Queued.";
            default -> "No reason recorded.";
        };
    }

    private static String truncate(String text) {
        return text == null ? null : text.length() <= 300 ? text : text.substring(0, 300);
    }
}
