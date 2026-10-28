package org.bunnys.bunnynexus.freebies;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
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
 * Automatic publishing (crossposting) jobs, {@code FreebiePublications}. {@code _id} is {@code <channelId>|<messageId>},
 * so planning is an idempotent insert. Publication state is separate from delivery state: a delivery is SENT when the
 * message exists in our channel; PUBLISHED only means Discord accepted the crosspost (or the message already carries
 * the CROSSPOSTED flag). It does not confirm delivery to any follower server.
 */
final class FreebiePublicationStore {
    static final String COLLECTION = "FreebiePublications";
    enum State { QUEUED, DELAYED, BLOCKED, UNCERTAIN, PUBLISHING, PUBLISHED, SKIPPED, CANCELLED, FAILED }
    static final List<String> OPEN = List.of("QUEUED", "DELAYED", "BLOCKED", "UNCERTAIN", "PUBLISHING");
    static final List<String> CLAIMABLE = List.of("QUEUED", "DELAYED", "BLOCKED", "UNCERTAIN");
    /** Discord: "a rate limit of 10 publishes per hour" per announcement channel (Announcement Channel FAQ). */
    static final int HOURLY_LIMIT = 10;
    static final int MAX_ATTEMPTS = 8;

    record Job(String id, String pageId, String guildId, String channelId, String messageId, int page, String key,
               int attempts, boolean uncertain, String token) {}

    private final MongoCollection<Document> jobs;
    FreebiePublicationStore(MongoDatabase db) { jobs = db.getCollection(COLLECTION); }

    void installIndexes() {
        jobs.createIndex(Indexes.ascending("state", "nextAttemptAt"), new IndexOptions().name("publication_due"));
        jobs.createIndex(Indexes.ascending("channelId", "publishedAt"), new IndexOptions().name("publication_channel"));
        jobs.createIndex(Indexes.ascending("key", "channelId", "page"), new IndexOptions().name("publication_page"));
    }

    static String id(String channelId, String messageId) { return channelId + "|" + messageId; }

    /** True only for the planner that created the job. */
    boolean plan(Document page, Instant now) {
        var doc = new Document("_id", id(page.getString("channelId"), page.getString("messageId"))).append("schemaVersion", 1)
                .append("pageId", page.getString("_id")).append("key", page.getString("key")).append("page", page.getInteger("page"))
                .append("guildId", page.getString("guildId")).append("channelId", page.getString("channelId"))
                .append("messageId", page.getString("messageId")).append("state", State.QUEUED.name()).append("attempts", 0)
                .append("uncertain", false).append("nextAttemptAt", Date.from(now)).append("createdAt", Date.from(now));
        try { jobs.insertOne(doc); return true; }
        catch (MongoWriteException duplicate) {
            if (duplicate.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) throw duplicate;
            return false;
        }
    }

    Optional<Job> claim(Instant now, Duration lease) {
        String token = UUID.randomUUID().toString();
        var d = jobs.findOneAndUpdate(and(in("state", CLAIMABLE), lte("nextAttemptAt", Date.from(now))),
                combine(set("state", State.PUBLISHING.name()), set("token", token), set("leaseUntil", Date.from(now.plus(lease))),
                        inc("attempts", 1), set("lastAttemptAt", Date.from(now))),
                new FindOneAndUpdateOptions().sort(Sorts.ascending("nextAttemptAt", "page", "_id")).returnDocument(ReturnDocument.AFTER));
        if (d == null) return Optional.empty();
        return Optional.of(new Job(d.getString("_id"), d.getString("pageId"), d.getString("guildId"), d.getString("channelId"),
                d.getString("messageId"), d.getInteger("page", 1), d.getString("key"), d.getInteger("attempts", 1),
                d.getBoolean("uncertain", false), token));
    }

    static Bson owned(Job job, Instant now) {
        return and(eq("_id", job.id()), eq("state", State.PUBLISHING.name()), eq("token", job.token()), gt("leaseUntil", Date.from(now)));
    }

    boolean owns(Job job, Instant now) { return jobs.countDocuments(owned(job, now)) == 1; }

    /** Persist that a crosspost request may be in flight, before submitting it. */
    boolean submitting(Job job, Instant now) {
        return jobs.updateOne(owned(job, now), combine(set("uncertain", true), set("submittedAt", Date.from(now)))).getModifiedCount() == 1;
    }

    private boolean finish(Job job, Instant now, Bson... changes) {
        var all = new ArrayList<>(List.of(changes));
        all.add(unset("token")); all.add(unset("leaseUntil"));
        return jobs.updateOne(owned(job, now), combine(all)).getModifiedCount() == 1;
    }

    boolean published(Job job, boolean reconciled, Instant now) {
        return finish(job, now, set("state", State.PUBLISHED.name()), set("publishedAt", Date.from(now)), set("uncertain", false),
                set("reconciled", reconciled), set("finishedAt", Date.from(now)), unset("reason"));
    }

    /** Waits without using up an attempt (ordering, rate budget, pending refresh). */
    boolean delay(Job job, String reason, Instant next, Instant now) {
        return finish(job, now, set("state", State.DELAYED.name()), set("reason", reason), set("nextAttemptAt", Date.from(next)),
                inc("attempts", -1));
    }

    /** A condition the owner must fix (channel type, permissions); checked again later. */
    boolean blocked(Job job, String reason, Instant next, Instant now) {
        return finish(job, now, set("state", State.BLOCKED.name()), set("reason", reason), set("nextAttemptAt", Date.from(next)));
    }

    /** The request may have reached Discord; the next attempt reads the CROSSPOSTED flag before anything else. */
    boolean uncertain(Job job, String reason, Instant next, Instant now) {
        return finish(job, now, set("state", State.UNCERTAIN.name()), set("uncertain", true), set("reason", reason),
                set("nextAttemptAt", Date.from(next)));
    }

    boolean skipped(Job job, String reason, Instant now) {
        return finish(job, now, set("state", State.SKIPPED.name()), set("reason", reason), set("finishedAt", Date.from(now)));
    }

    boolean cancelled(Job job, String reason, Instant now) {
        return finish(job, now, set("state", State.CANCELLED.name()), set("reason", reason), set("finishedAt", Date.from(now)));
    }

    boolean failed(Job job, String reason, Instant now) {
        return finish(job, now, set("state", State.FAILED.name()), set("reason", reason), set("finishedAt", Date.from(now)));
    }

    /** Leases left by a crash: the request may have been sent, so reconcile first. */
    long recover(Instant now) {
        return jobs.updateMany(and(eq("state", State.PUBLISHING.name()), lte("leaseUntil", Date.from(now))),
                combine(set("state", State.UNCERTAIN.name()), set("uncertain", true), set("reason", "lease expired"),
                        set("nextAttemptAt", Date.from(now)), unset("token"), unset("leaseUntil"))).getModifiedCount();
    }

    /** Publishing was turned off, or these channels left the allowlist: pending publications stop. */
    long cancelOpen(Optional<Set<String>> keepChannels, Instant now) {
        Bson filter = and(in("state", CLAIMABLE), keepChannels.map(keep -> nin("channelId", keep)).orElse(new Document()));
        return jobs.updateMany(filter, combine(set("state", State.CANCELLED.name()), set("reason", "publishing disabled"),
                set("finishedAt", Date.from(now)))).getModifiedCount();
    }

    /** Publishes this channel accepted in the last hour, including jobs that may be in flight. */
    long usedLastHour(String channelId, Instant now) {
        return jobs.countDocuments(and(eq("channelId", channelId), or(and(eq("state", State.PUBLISHED.name()),
                gt("publishedAt", Date.from(now.minus(Duration.ofHours(1))))), eq("state", State.PUBLISHING.name()),
                and(eq("uncertain", true), in("state", CLAIMABLE)))));
    }

    /** When the oldest publish counted in the last hour leaves the window. */
    Instant budgetFreesAt(String channelId, Instant now) {
        var oldest = jobs.find(and(eq("channelId", channelId), eq("state", State.PUBLISHED.name()),
                gt("publishedAt", Date.from(now.minus(Duration.ofHours(1)))))).sort(Sorts.ascending("publishedAt")).first();
        return oldest == null ? now.plus(Duration.ofMinutes(5)) : oldest.getDate("publishedAt").toInstant().plus(Duration.ofHours(1)).plusSeconds(5);
    }

    /** Earlier pages of the same announcement in this channel that are not finished yet. */
    boolean earlierPageOpen(Job job) {
        return job.page() > 1 && jobs.countDocuments(and(eq("key", job.key()), eq("channelId", job.channelId()),
                lt("page", job.page()), in("state", OPEN))) > 0;
    }

    Map<String, Long> counts() {
        Map<String, Long> counts = new TreeMap<>();
        for (Document d : jobs.aggregate(List.of(Aggregates.group("$state", Accumulators.sum("n", 1L)))))
            counts.put(d.getString("_id"), ((Number) d.get("n")).longValue());
        return counts;
    }

    Optional<Document> get(String id) { return Optional.ofNullable(jobs.find(eq("_id", id)).first()); }

    long purge(Instant cutoff, int limit) {
        Bson old = and(nin("state", OPEN), lt("finishedAt", Date.from(cutoff)));
        long deleted = 0;
        for (Document d : jobs.find(old).projection(Projections.include("_id")).limit(limit))
            deleted += jobs.deleteOne(and(eq("_id", d.getString("_id")), old)).getDeletedCount();
        return deleted;
    }
}
