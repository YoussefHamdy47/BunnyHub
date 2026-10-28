package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import java.time.*;
import java.util.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/**
 * Bounded, batched purge of finished free-game records older than the retention window. Every removal is a
 * single-document conditional delete (or replace) that repeats the full eligibility filter, so a record that
 * becomes active again between the read and the write is left alone.
 *
 * <p>Re-discovery guard: offers are deduplicated by GamerPower ID, so a purged offer is replaced in place by a
 * small tombstone ({@code state=PURGED}, ID and purge time) instead of being deleted. {@code recordSeen} upserts
 * with {@code $setOnInsert}, so a tombstoned ID that reappears in the feed is never inserted (or reviewed) again.
 * A "missing from the feed for longer than the window" rule alone was not enough: a long discovery outage leaves
 * {@code lastSeenAt} stale while the giveaway is still listed. Tombstones are about 100 bytes each and are kept.
 */
final class FreebieRetention {
    static final String PURGED = "PURGED", CONTROL_ID = "retention";
    static final Duration EVERY = Duration.ofHours(1), BACKLOG_EVERY = Duration.ofMinutes(1);
    record Limits(int deliveries, int offers, int notices, int batch) {
        static final Limits DEFAULT = new Limits(500, 100, 100, 100);
    }
    record Result(long deliveries, long offers, long notices, boolean more) {}
    record Last(Instant at, long deliveries, long offers, long notices) {}

    private static final List<String> TERMINAL_OFFER = List.of("REJECTED", "EXPIRED", "STOPPED", "COMPLETED");
    private final MongoCollection<Document> offers, deliveries, notices, control;
    private final FreebieLifecycleStore lifecycle;
    private final FreebieDmJobs dmJobs;
    private final FreebieAnnouncementStore announcements;
    private final FreebiePublicationStore publications;
    private final FreebieBroadcastStore broadcasts;
    /** Final edit/reminder jobs removed per run. */
    static final int JOBS_PER_RUN = 500;

    FreebieRetention(MongoDatabase db) {
        lifecycle = new FreebieLifecycleStore(db);
        dmJobs = new FreebieDmJobs(db);
        announcements = new FreebieAnnouncementStore(db);
        publications = new FreebiePublicationStore(db);
        broadcasts = new FreebieBroadcastStore(db);
        offers = db.getCollection(FreebieRepository.OFFERS);
        deliveries = db.getCollection(FreebieRepository.DELIVERIES);
        notices = db.getCollection(FreebieRepository.NOTICES);
        control = db.getCollection(FreebieRepository.CONTROL);
    }

    void installIndexes() {
        offers.createIndex(Indexes.ascending("state", "lastSeenAt"), new IndexOptions().name("offer_retention"));
        notices.createIndex(Indexes.ascending("lastNoticeAt"), new IndexOptions().name("notice_retention"));
    }

    boolean due(Instant now) {
        var doc = control.find(eq("_id", CONTROL_ID)).first();
        Date next = doc == null ? null : doc.getDate("nextPurgeAt");
        return next == null || !next.toInstant().isAfter(now);
    }

    Optional<Last> last() {
        var doc = control.find(eq("_id", CONTROL_ID)).first();
        if (doc == null || doc.getDate("lastPurgeAt") == null) return Optional.empty();
        return Optional.of(new Last(doc.getDate("lastPurgeAt").toInstant(), count(doc, "lastPurgeDeliveries"),
                count(doc, "lastPurgeOffers"), count(doc, "lastPurgeNotices")));
    }

    /** One bounded run: deliveries first (so their offers can follow in the same run), then offers, then notices. */
    Result purge(Instant now, int retentionDays, Limits limits) {
        Instant cutoff = now.minus(Duration.ofDays(retentionDays));
        long d = purgeDeliveries(now, cutoff, limits), o = purgeOffers(now, cutoff, limits), n = purgeNotices(now, cutoff, limits);
        // Finished card edits and reminders follow the same window; open ones are never removed.
        long j = lifecycle.purge(cutoff, JOBS_PER_RUN) + dmJobs.purge(now, cutoff, JOBS_PER_RUN)
                + publications.purge(cutoff, JOBS_PER_RUN) + announcements.purge(cutoff, JOBS_PER_RUN)
                + broadcasts.purge(cutoff, JOBS_PER_RUN);
        var result = new Result(d, o, n, d >= limits.deliveries() || o >= limits.offers() || n >= limits.notices() || j >= JOBS_PER_RUN);
        control.updateOne(eq("_id", CONTROL_ID), combine(set("lastPurgeAt", Date.from(now)), set("lastPurgeDeliveries", d),
                set("lastPurgeOffers", o), set("lastPurgeNotices", n), set("lastPurgeJobs", j), set("retentionDays", retentionDays),
                set("nextPurgeAt", Date.from(now.plus(result.more() ? BACKLOG_EVERY : EVERY)))), new UpdateOptions().upsert(true));
        return result;
    }

    // ------------------------------------------------------------------ deliveries

    /** Finished long ago, nothing leased, no owner notice or retry preview still depending on it. */
    static Bson oldDelivery(Instant now, Instant cutoff) {
        return and(in("state", "SENT", "FAILED", "CANCELLED"), lt("finishedAt", Date.from(cutoff)), ne("noticePending", true),
                exists("leaseToken", false), or(eq("leaseUntil", null), lte("leaseUntil", Date.from(now))),
                or(eq("retryPreviewUntil", null), lte("retryPreviewUntil", Date.from(now))));
    }

    private long purgeDeliveries(Instant now, Instant cutoff, Limits limits) {
        long deleted = 0, scanned = 0;
        Set<String> held = new HashSet<>(), busy = new HashSet<>();
        Map<String, Boolean> settled = new HashMap<>();
        while (deleted < limits.deliveries() && scanned < 4L * limits.deliveries()) {
            int size = (int) Math.min(limits.batch(), limits.deliveries() - deleted);
            var batch = deliveries.find(and(oldDelivery(now, cutoff), nin("offerId", held), nin("_id", busy)))
                    .projection(Projections.include("_id", "offerId", "announcementKey")).sort(Sorts.ascending("finishedAt", "_id"))
                    .limit(size).into(new ArrayList<>());
            if (batch.isEmpty()) break;
            scanned += batch.size();
            for (var row : batch) {
                String offerId = row.getString("offerId");
                if (!settled.computeIfAbsent(offerId, id -> offerSettled(id, now))) { held.add(offerId); continue; }
                // A card edit or reminder still queued for this alert needs its message ID.
                if (lifecycle.openFor(row.getString("_id"))) { busy.add(row.getString("_id")); continue; }
                // A page still being submitted, refreshed or published needs its member rows.
                if (row.getString("announcementKey") != null && announcements.openWorkFor(offerId)) { busy.add(row.getString("_id")); continue; }
                deleted += deliveries.deleteOne(and(eq("_id", row.get("_id")), oldDelivery(now, cutoff))).getDeletedCount();
            }
        }
        return deleted;
    }

    /**
     * A delivery may go only once its offer can never use it again: still-live offers keep their rows, because
     * the row's ID is what limits catch-up to once per channel; unsent summaries keep the counts they report.
     */
    private boolean offerSettled(String offerId, Instant now) {
        var offer = offers.find(eq("_id", offerId)).first();
        if (offer == null || PURGED.equals(offer.getString("state"))) return true;
        String state = offer.getString("state");
        if (!TERMINAL_OFFER.contains(state)) return false;
        Date leased = offer.getDate("summaryLeaseUntil");
        if (leased != null && leased.toInstant().isAfter(now)) return false;
        if ((state.equals("STOPPED") || state.equals("COMPLETED")) && !offer.getBoolean("summaryPosted", false)) return false;
        if (!state.equals("COMPLETED")) return true;
        Date ends = offer.getDate("endsAt");
        return offer.getBoolean("gone", false) || (ends != null && !ends.toInstant().isAfter(now));
    }

    // ------------------------------------------------------------------ offers

    /** Terminal, past its end, unseen in the feed for the whole window, summary settled and no lease of any kind. */
    static Bson oldOffer(Instant now, Instant cutoff) {
        Date at = Date.from(now), before = Date.from(cutoff);
        return and(in("state", TERMINAL_OFFER), lt("lastSeenAt", before),
                or(eq("endsAt", null), lte("endsAt", at)), or(eq("updatedAt", null), lt("updatedAt", before)),
                or(in("state", "REJECTED", "EXPIRED"), eq("summaryPosted", true)),
                or(eq("summaryLeaseUntil", null), lte("summaryLeaseUntil", at)),
                or(eq("reviewPostUntil", null), lte("reviewPostUntil", at)));
    }

    private long purgeOffers(Instant now, Instant cutoff, Limits limits) {
        long purged = 0;
        var candidates = offers.find(oldOffer(now, cutoff)).projection(Projections.include("_id", "source", "sourceId", "lastSeenAt", "sourceAliases", "matchKey", "sourceCandidates", "sourceDecisions", "separateCandidates", "sourceActors", "sourceDecidedAt"))
                .sort(Sorts.ascending("lastSeenAt", "_id")).limit(limits.offers()).into(new ArrayList<>());
        for (var offer : candidates) {
            String id = offer.getString("_id");
            if (deliveries.countDocuments(eq("offerId", id)) > 0) continue;
            if (dmJobs.openFor(id)) continue; // A personal DM still queued needs the offer's card.
            if (lifecycle.openForOffer(id)) continue; // Includes private cards whose edits are still queued.
            if (announcements.openWorkFor(id)) continue; // A posted page still shows this game.
            if (notices.countDocuments(and(eq("noticeOfferId", id), eq("noticeState", "PENDING"))) > 0) continue;
            var tombstone = new Document("_id", id).append("schemaVersion", 1).append("state", PURGED)
                    .append("source", offer.getString("source")).append("sourceId", offer.getString("sourceId"))
                    .append("lastSeenAt", offer.getDate("lastSeenAt")).append("purgedAt", Date.from(now));
            for (String field : List.of("sourceAliases", "matchKey", "sourceCandidates", "sourceDecisions", "separateCandidates", "sourceActors", "sourceDecidedAt"))
                if (offer.containsKey(field)) tombstone.put(field, offer.get(field));
            purged += offers.replaceOne(and(eq("_id", id), oldOffer(now, cutoff),
                    eq("sourceAliases", offer.get("sourceAliases")), eq("sourceCandidates", offer.get("sourceCandidates")),
                    eq("sourceDecisions", offer.get("sourceDecisions"))), tombstone).getModifiedCount();
        }
        return purged;
    }

    // ------------------------------------------------------------------ notices

    /** Delivered and reconciled (or legacy daily-limit only) with its daily limit long expired, or dropped; unleased. */
    static Bson oldNotice(Instant now, Instant cutoff) {
        Instant limitEnds = now.minus(Duration.ofDays(1));
        return and(or(and(lt("lastNoticeAt", Date.from(cutoff.isBefore(limitEnds) ? cutoff : limitEnds)),
                        or(and(eq("noticeState", "SENT"), eq("noticeReconciled", true)), exists("noticeState", false))),
                        // Settings-change notices for a server the bot has left.
                        and(eq("noticeState", "DROPPED"), lt("noticeDroppedAt", Date.from(cutoff)))),
                or(eq("noticeLeaseUntil", null), lte("noticeLeaseUntil", Date.from(now))));
    }

    private long purgeNotices(Instant now, Instant cutoff, Limits limits) {
        long deleted = 0;
        var candidates = notices.find(oldNotice(now, cutoff)).projection(Projections.include("_id"))
                .sort(Sorts.ascending("lastNoticeAt", "_id")).limit(limits.notices()).into(new ArrayList<>());
        for (var notice : candidates) {
            String guild = notice.getString("_id");
            // An unprocessed failure in this server is still judged against lastNoticeAt; keep it until then.
            if (deliveries.countDocuments(and(eq("guildId", guild), eq("noticePending", true))) > 0) continue;
            deleted += notices.deleteOne(and(eq("_id", guild), oldNotice(now, cutoff))).getDeletedCount();
        }
        return deleted;
    }

    private static long count(Document doc, String key) {
        Object value = doc.get(key);
        return value instanceof Number number ? number.longValue() : 0;
    }
}


