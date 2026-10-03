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
 * Offer, delivery, notice and control persistence. Every state change is a single-document conditional update,
 * so a double click, a second bot process or a crash-and-restart cannot bypass the owner's approval.
 * Discord delivery uses leases and duplicate mitigation, not an exactly-once guarantee.
 * Server settings live in {@link FreebieSubscriptions}.
 */
public final class FreebieRepository {
    public static final String OFFERS = "FreebieOffers", DELIVERIES = "FreebieDeliveries",
            NOTICES = "FreebieNotices", CONTROL = "FreebieControl";
    private static final int INSERT_BATCH = 500;

    public enum DeliveryState { PENDING, SENDING, SENT, FAILED, BLOCKED, CANCELLED }
    public record Claimed(String id, String offerId, String guildId, String channelId, Optional<String> roleId,
                          int attempts, boolean verifyFirst, String token) {}
    public record Counts(long sent, long failed, long cancelled, long open) {}
    public record FailedDelivery(String guildId, String channelId, FreebieFailure reason, boolean serverNotified) {}
    public record Overview(long pendingReviews, long sending, long queuedDeliveries, long retrying) {}

    private final MongoCollection<Document> offers, deliveries, notices, control;
    private final FreebieSummaryStore summaries;
    private final FreebieHealth health;
    private final FreebieNoticeStore noticeStore;
    FreebieNoticeStore notices() { return noticeStore; }
    private final FreebieReviewStore reviews;
    FreebieHealth health() { return health; }
    FreebieReviewStore reviews() { return reviews; }
    FreebieSummaryStore summaries() { return summaries; }
    private final FreebieDeliveryStore deliveryStore;
    FreebieDeliveryStore deliveries() { return deliveryStore; }

    public FreebieRepository(MongoDatabase db) {
        offers = db.getCollection(OFFERS); deliveries = db.getCollection(DELIVERIES);
        notices = db.getCollection(NOTICES); control = db.getCollection(CONTROL);
        summaries = new FreebieSummaryStore(offers);
        reviews = new FreebieReviewStore(offers);
        health = new FreebieHealth(db);
        noticeStore = new FreebieNoticeStore(notices, deliveries);
        deliveryStore = new FreebieDeliveryStore(deliveries);
    }

    public void installIndexes() {
        summaries.installIndexes();
        health.installIndexes();
        noticeStore.installIndexes();
        deliveryStore.installIndexes();
        offers.createIndex(Indexes.ascending("state", "reviewMessageId"), new IndexOptions().name("offer_state"));
        deliveries.createIndex(Indexes.ascending("state", "nextAttemptAt"), new IndexOptions().name("delivery_due"));
        deliveries.createIndex(Indexes.ascending("state", "leaseUntil"), new IndexOptions().name("delivery_lease"));
        deliveries.createIndex(Indexes.ascending("offerId", "state"), new IndexOptions().name("delivery_offer"));
    }

    // ---------------------------------------------------------------- offers

    /** True only for the call that first records this giveaway. */
    public boolean recordSeen(GamerPowerIntake.Candidate candidate, Instant now) {
        Document fresh = FreebieOffer.newDocument(candidate, now);
        fresh.remove("lastSeenAt"); fresh.remove("missingPolls");
        String id = fresh.getString("_id"); fresh.remove("_id");
        var result = offers.updateOne(eq("_id", id), combine(new Document("$setOnInsert", fresh),
                set("lastSeenAt", Date.from(now)), set("missingPolls", 0), set("gone", false)), new UpdateOptions().upsert(true));
        return result.getUpsertedId() != null;
    }

    /**
     * After a complete feed, count consecutive polls in which a live offer was absent. The second miss marks it
     * gone (ended) and returns it once, so callers can close its review or stop its sends.
     */
    public List<FreebieOffer> markMissing(Set<String> presentIds) {
        Bson absentLive = and(in("state", "PENDING", "APPROVED", "COMPLETED"), nin("_id", presentIds), ne("gone", true));
        offers.updateMany(absentLive, inc("missingPolls", 1));
        List<String> gone = offers.find(and(absentLive, gte("missingPolls", 2))).projection(Projections.include("_id")).limit(200)
                .map(d -> d.getString("_id")).into(new ArrayList<>());
        if (gone.isEmpty()) return List.of();
        offers.updateMany(and(in("_id", gone), ne("gone", true)), set("gone", true));
        return offers.find(in("_id", gone)).map(FreebieOffer::read).into(new ArrayList<>());
    }

    public Optional<FreebieOffer> offer(String id) {
        return Optional.ofNullable(offers.find(eq("_id", id)).first()).map(FreebieOffer::read);
    }

    public List<FreebieOffer> offersIn(FreebieOffer.State state, int limit) {
        return offers.find(eq("state", state.name())).sort(Sorts.ascending("discoveredAt")).limit(limit)
                .map(FreebieOffer::read).into(new ArrayList<>());
    }

    /** Conditional state change; empty when someone else already moved it (double click, other owner, expiry). */
    public Optional<FreebieOffer> transition(String offerId, FreebieOffer.State from, FreebieOffer.State to,
                                             String actorId, Instant now) {
        List<Bson> changes = new ArrayList<>(List.of(set("state", to.name()), inc("revision", 1L), set("updatedAt", Date.from(now))));
        if (actorId != null) { changes.add(set("decidedBy", actorId)); changes.add(set("decidedAt", Date.from(now))); }
        return update(and(eq("_id", offerId), eq("state", from.name())), combine(changes));
    }

    /** Owner correction of the auto-detected launcher; only while the offer is still awaiting review. */
    public Optional<FreebieOffer> changeStore(String offerId, FreebieStore store, Instant now) {
        return update(and(eq("_id", offerId), eq("state", "PENDING")),
                combine(set("store", store.id()), set("storeOverridden", true), inc("revision", 1L), set("updatedAt", Date.from(now))));
    }

    private Optional<FreebieOffer> update(Bson filter, Bson change) {
        var doc = offers.findOneAndUpdate(filter, change, new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return Optional.ofNullable(doc).map(FreebieOffer::read);
    }

    /** Approved giveaways that are still live, for a channel that subscribed after they were sent. */
    public List<FreebieOffer> liveApproved(FreebieStore store, Instant now) {
        return liveApproved(Optional.of(store), now, 10);
    }

    /**
     * Owner-approved giveaways that are still free, newest first; every launcher when {@code store} is empty.
     * Backs both late-subscriber catch-up and the public {@code /free-games} list, so neither can show a game
     * the owner never approved, rejected or stopped.
     */
    public List<FreebieOffer> liveApproved(Optional<FreebieStore> store, Instant now, int limit) {
        List<Bson> filters = new ArrayList<>(List.of(in("state", "APPROVED", "COMPLETED"), ne("gone", true),
                or(eq("endsAt", null), gt("endsAt", Date.from(now)))));
        store.ifPresent(s -> filters.add(eq("store", s.id())));
        return offers.find(and(filters)).sort(Sorts.descending("discoveredAt")).limit(limit)
                .map(FreebieOffer::read).into(new ArrayList<>());
    }

    /** Several fan-outs of one offer may race; the recorded plan size never shrinks. */
    public void setFanoutDone(String offerId, int planned) {
        offers.updateOne(eq("_id", offerId), combine(set("fanoutDone", true), max("plannedDeliveries", planned)));
    }

    // ---------------------------------------------------------------- global control

    /** Global kill switch for sending (discovery and reviews continue). Survives restarts. */
    public boolean sendingPaused() {
        var doc = control.find(eq("_id", "global")).first();
        return doc != null && doc.getBoolean("paused", false);
    }

    public void setSendingPaused(boolean paused, String actorId, Instant now) {
        control.updateOne(eq("_id", "global"), combine(set("paused", paused), set("updatedBy", actorId), set("updatedAt", Date.from(now))),
                new UpdateOptions().upsert(true));
    }

    public Overview overview() {
        return new Overview(offers.countDocuments(eq("state", "PENDING")), offers.countDocuments(eq("state", "APPROVED")),
                deliveries.countDocuments(in("state", "PENDING", "SENDING", "BLOCKED")),
                deliveries.countDocuments(and(eq("state", "PENDING"), gt("attempts", 0))));
    }

    // ---------------------------------------------------------------- deliveries

    /** Idempotent: one delivery per offer+channel, so a crashed/retried fan-out never creates duplicates. */
    public int planDeliveries(FreebieOffer offer, Iterable<FreebieSubscriptions.Subscription> audience, Instant now) {
        int planned = 0;
        List<Document> batch = new ArrayList<>();
        for (var subscription : audience) {
            batch.add(delivery(offer, subscription, false, now));
            if (batch.size() == INSERT_BATCH) { planned += insertIgnoringDuplicates(batch); batch.clear(); }
        }
        if (!batch.isEmpty()) planned += insertIgnoringDuplicates(batch);
        return planned;
    }

    /** Catch-up for one channel; false when it already has (or had) a delivery for this offer. */
    public boolean planDelivery(FreebieOffer offer, FreebieSubscriptions.Subscription subscription, Instant now) {
        return insertIgnoringDuplicates(List.of(delivery(offer, subscription, true, now))) == 1;
    }

    private static Document delivery(FreebieOffer offer, FreebieSubscriptions.Subscription s, boolean catchUp, Instant now) {
        var doc = new Document("_id", offer.id() + "|" + s.channelId()).append("schemaVersion", 1).append("offerId", offer.id())
                .append("guildId", s.guildId()).append("channelId", s.channelId()).append("roleId", s.roleId().orElse(null))
                .append("state", DeliveryState.PENDING.name()).append("attempts", 0).append("verifyFirst", false);
        if (catchUp) doc.append("catchUp", true);
        return doc.append("nextAttemptAt", Date.from(now)).append("createdAt", Date.from(now));
    }

    private int insertIgnoringDuplicates(List<Document> batch) {
        try {
            return deliveries.insertMany(batch, new InsertManyOptions().ordered(false)).getInsertedIds().size();
        } catch (MongoBulkWriteException partial) {
            if (partial.getWriteErrors().stream().anyMatch(e -> ErrorCategory.fromErrorCode(e.getCode()) != ErrorCategory.DUPLICATE_KEY))
                throw partial;
            return batch.size() - partial.getWriteErrors().size();
        }
    }

    /** A process that died mid-send leaves SENDING behind; the next attempt must first look for the message. */
    public long recoverExpiredLeases(Instant now) {
        return deliveries.updateMany(and(eq("state", "SENDING"), lte("leaseUntil", Date.from(now))),
                combine(set("state", "BLOCKED"), set("blockedReason", "UNCERTAIN"), set("blockedAt", Date.from(now)),
                        inc("deliveryRevision", 1L), set("verifyFirst", true), set("nextAttemptAt", Date.from(now)),
                        unset("leaseToken"), unset("leaseUntil"))).getModifiedCount();
    }

    public Optional<Claimed> claim(Instant now, Duration lease) {
        String token = UUID.randomUUID().toString();
        var doc = deliveries.findOneAndUpdate(and(in("state", "PENDING", "BLOCKED"), lte("nextAttemptAt", Date.from(now))),
                combine(set("state", "SENDING"), set("leaseToken", token), set("leaseUntil", Date.from(now.plus(lease))),
                        inc("attempts", 1), set("lastAttemptAt", Date.from(now))),
                new FindOneAndUpdateOptions().sort(Sorts.ascending("nextAttemptAt")).returnDocument(ReturnDocument.AFTER));
        if (doc == null) return Optional.empty();
        return Optional.of(new Claimed(doc.getString("_id"), doc.getString("offerId"), doc.getString("guildId"),
                doc.getString("channelId"), Optional.ofNullable(doc.getString("roleId")), doc.getInteger("attempts"),
                doc.getBoolean("verifyFirst", false), token));
    }

    private boolean finish(Claimed c, Instant now, Bson... changes) {
        var all = new ArrayList<>(List.of(changes));
        all.add(unset("leaseToken")); all.add(unset("leaseUntil"));
        all.add(inc("deliveryRevision", 1L));
        return deliveries.updateOne(FreebieDeliveryStore.owned(c, now), combine(all))
                .getModifiedCount() == 1;
    }

    public boolean markSent(Claimed c, String messageId, Instant now) {
        return finish(c, now, set("state", "SENT"), set("messageId", messageId), set("finishedAt", Date.from(now)), unset("failure"), unset("blockedReason"), set("verifyFirst", false));
    }

    public boolean markRetry(Claimed c, FreebieFailure failure, String detail, Instant next, boolean verifyFirst, Instant now) {
        return finish(c, now, set("state", "PENDING"), set("nextAttemptAt", Date.from(next)), set("verifyFirst", verifyFirst),
                set("failure", failure.name()), set("failureDetail", truncate(detail)));
    }

    public boolean markFailed(Claimed c, FreebieFailure failure, String detail, Instant now) {
        return finish(c, now, set("state", "FAILED"), set("failure", failure.name()), set("failureDetail", truncate(detail)),
                set("finishedAt", Date.from(now)), set("verifyFirst", c.verifyFirst()), set("noticePending", failure.notifyServer()));
    }

    public boolean markCancelled(Claimed c, String reason, Instant now) {
        return finish(c, now, set("state", "CANCELLED"), set("failureDetail", reason), set("finishedAt", Date.from(now)));
    }

    /** Release without using up an attempt (e.g. Discord gateway temporarily disconnected). */
    public void release(Claimed c, Instant next, Instant now) {
        finish(c, now, set("state", "PENDING"), set("nextAttemptAt", Date.from(next)), inc("attempts", -1));
    }

    public long cancelPending(String offerId, String reason, Instant now) {
        return deliveries.updateMany(and(eq("offerId", offerId), in("state", "PENDING", "BLOCKED")),
                combine(set("state", "CANCELLED"), set("failureDetail", reason), set("finishedAt", Date.from(now)))).getModifiedCount();
    }

    public Counts counts(String offerId) {
        Map<String, Long> byState = new HashMap<>();
        for (Document d : deliveries.aggregate(List.of(Aggregates.match(eq("offerId", offerId)),
                Aggregates.group("$state", Accumulators.sum("n", 1L)))))
            byState.put(d.getString("_id"), ((Number) d.get("n")).longValue());
        return new Counts(byState.getOrDefault("SENT", 0L), byState.getOrDefault("FAILED", 0L),
                byState.getOrDefault("CANCELLED", 0L),
                byState.getOrDefault("PENDING", 0L) + byState.getOrDefault("SENDING", 0L) + byState.getOrDefault("BLOCKED", 0L));
    }

    public List<FailedDelivery> failures(String offerId, int limit) {
        return deliveries.find(and(eq("offerId", offerId), eq("state", "FAILED"))).sort(Sorts.ascending("_id")).limit(limit)
                .map(d -> new FailedDelivery(d.getString("guildId"), d.getString("channelId"),
                        FreebieFailure.valueOf(d.getString("failure")), d.getBoolean("serverNotified", false)))
                .into(new ArrayList<>());
    }

    private static String truncate(String text) {
        return text == null ? null : text.length() <= 300 ? text : text.substring(0, 300);
    }
}



