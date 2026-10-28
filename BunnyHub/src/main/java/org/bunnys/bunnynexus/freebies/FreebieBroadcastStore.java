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
 * Owner announcements to every subscribed server. {@code FreebieBroadcasts} holds the text and its state
 * (DRAFT → SENDING → DONE, or CANCELLED); {@code FreebieBroadcastJobs} holds one leased job per server
 * ({@code _id} {@code <broadcastId>|<guildId>}), so planning is an idempotent insert. Every state change is a
 * single-document conditional update; job outcomes need the current lease token.
 */
final class FreebieBroadcastStore {
    static final String BROADCASTS = "FreebieBroadcasts", JOBS = "FreebieBroadcastJobs";
    static final Duration DRAFT_LIFETIME = Duration.ofMinutes(15);
    static final List<String> OPEN = List.of("PENDING", "SENDING");
    private final MongoCollection<Document> broadcasts, jobs;

    FreebieBroadcastStore(MongoDatabase db) {
        broadcasts = db.getCollection(BROADCASTS);
        jobs = db.getCollection(JOBS);
    }

    void installIndexes() {
        broadcasts.createIndex(Indexes.ascending("state", "createdAt"), new IndexOptions().name("broadcast_state"));
        jobs.createIndex(Indexes.ascending("state", "dueAt"), new IndexOptions().name("broadcast_job_due"));
        jobs.createIndex(Indexes.ascending("state", "leaseUntil"), new IndexOptions().name("broadcast_job_lease"));
        jobs.createIndex(Indexes.ascending("broadcastId", "state"), new IndexOptions().name("broadcast_job_owner"));
    }

    /** {@code ping}: the owner chose "Send with ping" (each server's saved free-game role); false otherwise. */
    /**
     * {@code section}: only servers with an alert channel for this section code (null: every server, main channel).
     * {@code fallback}: a server without that section gets the announcement in its main channel instead of nothing.
     */
    record Broadcast(String id, String title, String body, String state, String actor, boolean ping, String section, boolean fallback) {
        Broadcast(String id, String title, String body, String state, String actor, boolean ping) {
            this(id, title, body, state, actor, ping, null, false);
        }
        Broadcast(String id, String title, String body, String state, String actor) { this(id, title, body, state, actor, false); }
        static Broadcast read(Document d) {
            return new Broadcast(d.getString("_id"), d.getString("title"), d.getString("body"), d.getString("state"), d.getString("actor"),
                    d.getBoolean("ping", false), d.getString("section"), d.getBoolean("fallback", false));
        }
    }
    /** {@code roleId}: the server's saved free-game role for the chosen channel, if any. */
    record Job(String id, String broadcastId, String guildId, String channelId, int attempts, boolean verifyFirst, String token,
               java.util.Optional<String> roleId) {
        Job(String id, String broadcastId, String guildId, String channelId, int attempts, boolean verifyFirst, String token) {
            this(id, broadcastId, guildId, channelId, attempts, verifyFirst, token, java.util.Optional.empty());
        }
    }
    record Counts(long sent, long failed, long skipped, long cancelled, long open) {
        long total() { return sent + failed + skipped + cancelled + open; }
    }

    /** A draft the owner previewed; only that owner can send it, within {@link #DRAFT_LIFETIME}. */
    Broadcast draft(String title, String body, String actor, String token, Instant now) { return draft(title, body, actor, token, now, null, false); }

    /** The target is saved with the draft, so planning (and recovery after a crash) uses exactly what was previewed. */
    Broadcast draft(String title, String body, String actor, String token, Instant now, String section, boolean fallback) {
        String id = "bc" + Long.toString(now.toEpochMilli(), 36) + token.substring(0, 6);
        broadcasts.insertOne(new Document("_id", id).append("schemaVersion", 1).append("title", title).append("body", body)
                .append("state", "DRAFT").append("actor", actor).append("token", token).append("section", section).append("fallback", fallback)
                .append("until", Date.from(now.plus(DRAFT_LIFETIME))).append("createdAt", Date.from(now)));
        return new Broadcast(id, title, body, "DRAFT", actor, false, section, fallback);
    }

    /** DRAFT → SENDING, once, by the owner who previewed it, before the draft expires. */
    Optional<Broadcast> confirm(String id, String token, String actor, Instant now) { return confirm(id, token, actor, now, false); }

    /** {@code ping}: saved with the confirmation, in the same conditional write. */
    Optional<Broadcast> confirm(String id, String token, String actor, Instant now, boolean ping) {
        var d = broadcasts.findOneAndUpdate(and(eq("_id", id), eq("state", "DRAFT"), eq("token", token), eq("actor", actor),
                        gt("until", Date.from(now))),
                combine(set("state", "SENDING"), set("confirmedAt", Date.from(now)), set("ping", ping), unset("token")),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return Optional.ofNullable(d).map(Broadcast::read);
    }

    Optional<Broadcast> get(String id) { return Optional.ofNullable(broadcasts.find(eq("_id", id)).first()).map(Broadcast::read); }

    /** Broadcasts confirmed but not yet fully planned (a crash during planning). */
    List<Broadcast> unplanned(int limit) {
        return broadcasts.find(and(eq("state", "SENDING"), ne("planned", true))).limit(limit).map(Broadcast::read).into(new ArrayList<>());
    }

    void planned(String id, int servers) {
        broadcasts.updateOne(eq("_id", id), combine(set("planned", true), max("servers", servers)));
    }

    /** One job per server; idempotent. */
    int plan(String broadcastId, Map<String, String> channelByGuild, Instant now) {
        Map<String, FreebieSubscriptions.Subscription> targets = new TreeMap<>();
        channelByGuild.forEach((guild, channel) -> targets.put(guild, new FreebieSubscriptions.Subscription(guild, channel, FreebieStore.OTHER, Optional.empty())));
        return planTargets(broadcastId, targets, now);
    }

    /** One job per server, with the chosen channel and its saved role; idempotent. */
    int planTargets(String broadcastId, Map<String, FreebieSubscriptions.Subscription> targets, Instant now) {
        List<Document> batch = new ArrayList<>();
        targets.forEach((guild, s) -> batch.add(new Document("_id", broadcastId + "|" + guild).append("schemaVersion", 1)
                .append("broadcastId", broadcastId).append("guildId", guild).append("channelId", s.channelId())
                .append("roleId", s.roleId().orElse(null)).append("state", "PENDING")
                .append("attempts", 0).append("verifyFirst", false).append("dueAt", Date.from(now)).append("createdAt", Date.from(now))));
        if (batch.isEmpty()) return 0;
        try { return jobs.insertMany(batch, new InsertManyOptions().ordered(false)).getInsertedIds().size(); }
        catch (MongoBulkWriteException partial) {
            if (partial.getWriteErrors().stream().anyMatch(e -> ErrorCategory.fromErrorCode(e.getCode()) != ErrorCategory.DUPLICATE_KEY))
                throw partial;
            return batch.size() - partial.getWriteErrors().size();
        }
    }

    /** The owner stopped it: queued servers are cancelled; up to the in-flight ones may still arrive. */
    long cancel(String id, String actor, Instant now) {
        var changed = broadcasts.updateOne(and(eq("_id", id), in("state", "DRAFT", "SENDING")),
                combine(set("state", "CANCELLED"), set("cancelledBy", actor), set("cancelledAt", Date.from(now)), unset("token")));
        if (changed.getModifiedCount() == 0) return -1;
        return jobs.updateMany(and(eq("broadcastId", id), eq("state", "PENDING")),
                combine(set("state", "CANCELLED"), set("finishedAt", Date.from(now)))).getModifiedCount();
    }

    /** SENDING → DONE once every job is final and planning finished. True only for the call that finished it. */
    boolean finishIfDone(String id, Instant now) {
        if (jobs.countDocuments(and(eq("broadcastId", id), in("state", OPEN))) > 0) return false;
        return broadcasts.updateOne(and(eq("_id", id), eq("state", "SENDING"), eq("planned", true)),
                combine(set("state", "DONE"), set("finishedAt", Date.from(now)))).getModifiedCount() == 1;
    }

    List<Broadcast> sending(int limit) {
        return broadcasts.find(eq("state", "SENDING")).sort(Sorts.ascending("createdAt")).limit(limit).map(Broadcast::read).into(new ArrayList<>());
    }

    Counts counts(String id) {
        Map<String, Long> by = new HashMap<>();
        for (Document d : jobs.aggregate(List.of(Aggregates.match(eq("broadcastId", id)), Aggregates.group("$state", Accumulators.sum("n", 1L)))))
            by.put(d.getString("_id"), ((Number) d.get("n")).longValue());
        return new Counts(by.getOrDefault("SENT", 0L), by.getOrDefault("FAILED", 0L), by.getOrDefault("SKIPPED", 0L),
                by.getOrDefault("CANCELLED", 0L), by.getOrDefault("PENDING", 0L) + by.getOrDefault("SENDING", 0L));
    }

    // ------------------------------------------------------------------ jobs

    long recover(Instant now) {
        return jobs.updateMany(and(eq("state", "SENDING"), lte("leaseUntil", Date.from(now))),
                combine(set("state", "PENDING"), set("verifyFirst", true), set("dueAt", Date.from(now)),
                        unset("leaseToken"), unset("leaseUntil"))).getModifiedCount();
    }

    Optional<Job> claim(Instant now, Duration lease) {
        String token = UUID.randomUUID().toString();
        var d = jobs.findOneAndUpdate(and(eq("state", "PENDING"), lte("dueAt", Date.from(now))),
                combine(set("state", "SENDING"), set("leaseToken", token), set("leaseUntil", Date.from(now.plus(lease))),
                        inc("attempts", 1), set("lastAttemptAt", Date.from(now))),
                new FindOneAndUpdateOptions().sort(Sorts.ascending("dueAt", "_id")).returnDocument(ReturnDocument.AFTER));
        if (d == null) return Optional.empty();
        return Optional.of(new Job(d.getString("_id"), d.getString("broadcastId"), d.getString("guildId"), d.getString("channelId"),
                d.getInteger("attempts", 1), d.getBoolean("verifyFirst", false), token, Optional.ofNullable(d.getString("roleId"))));
    }

    static Bson owned(Job job, Instant now) {
        return and(eq("_id", job.id()), eq("state", "SENDING"), eq("leaseToken", job.token()), gt("leaseUntil", Date.from(now)));
    }

    boolean owns(Job job, Instant now) { return jobs.countDocuments(owned(job, now)) == 1; }

    /** Persist uncertainty before the request, so a crash after acceptance checks history instead of resending. */
    boolean submitting(Job job, Instant now) {
        return jobs.updateOne(owned(job, now), combine(set("verifyFirst", true), set("submittedAt", Date.from(now)))).getModifiedCount() == 1;
    }

    private boolean finish(Job job, Instant now, Bson... changes) {
        var all = new ArrayList<>(List.of(changes));
        all.add(unset("leaseToken")); all.add(unset("leaseUntil"));
        return jobs.updateOne(owned(job, now), combine(all)).getModifiedCount() == 1;
    }

    boolean sent(Job job, String messageId, Instant now) {
        return finish(job, now, set("state", "SENT"), set("messageId", messageId), set("finishedAt", Date.from(now)), unset("reason"));
    }

    boolean skipped(Job job, String reason, Instant now) {
        return finish(job, now, set("state", "SKIPPED"), set("reason", reason), set("finishedAt", Date.from(now)));
    }

    boolean cancelled(Job job, Instant now) {
        return finish(job, now, set("state", "CANCELLED"), set("finishedAt", Date.from(now)));
    }

    boolean failed(Job job, String reason, Instant now) {
        return finish(job, now, set("state", "FAILED"), set("reason", reason), set("finishedAt", Date.from(now)));
    }

    boolean retry(Job job, Instant next, String reason, Instant now) {
        return finish(job, now, set("state", "PENDING"), set("dueAt", Date.from(next)), set("reason", reason));
    }

    boolean release(Job job, Instant next, Instant now) {
        return finish(job, now, set("state", "PENDING"), set("dueAt", Date.from(next)), inc("attempts", -1));
    }

    /** Final jobs and finished broadcasts older than the retention window. */
    long purge(Instant cutoff, int limit) {
        long deleted = 0;
        Bson oldJob = and(nin("state", OPEN), lt("finishedAt", Date.from(cutoff)));
        for (Document d : jobs.find(oldJob).projection(Projections.include("_id")).limit(limit))
            deleted += jobs.deleteOne(and(eq("_id", d.getString("_id")), oldJob)).getDeletedCount();
        Bson oldBroadcast = or(and(in("state", "DONE", "CANCELLED"), lt("createdAt", Date.from(cutoff))),
                and(eq("state", "DRAFT"), lt("until", Date.from(cutoff))));
        for (Document d : broadcasts.find(oldBroadcast).projection(Projections.include("_id")).limit(limit))
            if (jobs.countDocuments(eq("broadcastId", d.getString("_id"))) == 0)
                deleted += broadcasts.deleteOne(and(eq("_id", d.getString("_id")), oldBroadcast)).getDeletedCount();
        return deleted;
    }
}
