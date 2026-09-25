package org.bunnys.bunnynexus.freebies;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bunnys.bunnynexus.alerts.adapters.providers.GamerPowerIntake;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/**
 * All freebie persistence. Every state change is a single-document conditional update, so a double click,
 * a second bot process or a crash-and-restart cannot send an offer twice or skip the owner's approval.
 * Collections are new and independent of the older inactive Alert* collections.
 */
public final class FreebieRepository {
    public static final String OFFERS = "FreebieOffers", DELIVERIES = "FreebieDeliveries",
            SUBSCRIPTIONS = "FreebieSubscriptions", NOTICES = "FreebieNotices", CONTROL = "FreebieControl";
    public static final int MAX_SUBSCRIPTIONS_PER_GUILD = 10;

    public enum DeliveryState { PENDING, SENDING, SENT, FAILED, CANCELLED }
    public record Subscription(String guildId, String channelId, FreebieStore store, Optional<String> roleId) {}
    public record Claimed(String id, String offerId, String guildId, String channelId, Optional<String> roleId,
                          int attempts, boolean verifyFirst, String token) {}
    public record Counts(long sent, long failed, long cancelled, long open) {}
    public record FailedDelivery(String guildId, String channelId, FreebieFailure reason, boolean serverNotified) {}
    public record Audience(int channels, int guilds) {}

    private final MongoCollection<Document> offers, deliveries, subscriptions, notices, control;

    public FreebieRepository(MongoDatabase db) {
        offers = db.getCollection(OFFERS); deliveries = db.getCollection(DELIVERIES);
        subscriptions = db.getCollection(SUBSCRIPTIONS); notices = db.getCollection(NOTICES); control = db.getCollection(CONTROL);
    }

    public void installIndexes() {
        offers.createIndex(Indexes.ascending("state", "reviewMessageId"), new IndexOptions().name("offer_state"));
        deliveries.createIndex(Indexes.ascending("state", "nextAttemptAt"), new IndexOptions().name("delivery_due"));
        deliveries.createIndex(Indexes.ascending("state", "leaseUntil"), new IndexOptions().name("delivery_lease"));
        deliveries.createIndex(Indexes.ascending("offerId", "state"), new IndexOptions().name("delivery_offer"));
        subscriptions.createIndex(Indexes.ascending("store", "_id"), new IndexOptions().name("subscription_store"));
        subscriptions.createIndex(Indexes.ascending("guildId"), new IndexOptions().name("subscription_guild"));
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

    /** Lease a pending offer whose review message still has to be posted (retried if posting failed/crashed). */
    public Optional<FreebieOffer> claimReviewPost(Instant now) {
        var doc = offers.findOneAndUpdate(and(eq("state", "PENDING"), eq("reviewMessageId", null),
                        or(eq("reviewPostUntil", null), lte("reviewPostUntil", Date.from(now)))),
                set("reviewPostUntil", Date.from(now.plus(Duration.ofMinutes(2)))),
                new FindOneAndUpdateOptions().sort(Sorts.ascending("discoveredAt")).returnDocument(ReturnDocument.AFTER));
        return Optional.ofNullable(doc).map(FreebieOffer::read);
    }

    public void setReviewMessage(String offerId, String messageId) {
        offers.updateOne(eq("_id", offerId), combine(set("reviewMessageId", messageId), unset("reviewPostUntil")));
    }

    /** Conditional state change; empty when someone else already moved it (double click, other owner, expiry). */
    public Optional<FreebieOffer> transition(String offerId, FreebieOffer.State from, FreebieOffer.State to,
                                             String actorId, Instant now) {
        List<Bson> changes = new ArrayList<>(List.of(set("state", to.name()), inc("revision", 1L), set("updatedAt", Date.from(now))));
        if (actorId != null) { changes.add(set("decidedBy", actorId)); changes.add(set("decidedAt", Date.from(now))); }
        var doc = offers.findOneAndUpdate(and(eq("_id", offerId), eq("state", from.name())), combine(changes),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return Optional.ofNullable(doc).map(FreebieOffer::read);
    }


    /** Owner correction of the auto-detected launcher; only while the offer is still awaiting review. */
    public Optional<FreebieOffer> changeStore(String offerId, FreebieStore store, Instant now) {
        var doc = offers.findOneAndUpdate(and(eq("_id", offerId), eq("state", "PENDING")),
                combine(set("store", store.id()), set("storeOverridden", true), inc("revision", 1L), set("updatedAt", Date.from(now))),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return Optional.ofNullable(doc).map(FreebieOffer::read);
    }

    /** Approved giveaways that are still live, for a channel that subscribed after they were sent. */
    public List<FreebieOffer> liveApproved(FreebieStore store, Instant now) {
        return offers.find(and(eq("store", store.id()), in("state", "APPROVED", "COMPLETED"), ne("gone", true),
                        or(eq("endsAt", null), gt("endsAt", Date.from(now)))))
                .sort(Sorts.descending("discoveredAt")).limit(10).map(FreebieOffer::read).into(new ArrayList<>());
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

    public record Overview(long pendingReviews, long sending, long queuedDeliveries, long retrying) {}
    public Overview overview() {
        return new Overview(offers.countDocuments(eq("state", "PENDING")), offers.countDocuments(eq("state", "APPROVED")),
                deliveries.countDocuments(in("state", "PENDING", "SENDING")),
                deliveries.countDocuments(and(eq("state", "PENDING"), gt("attempts", 0))));
    }
    public void setFanoutDone(String offerId, int planned) {
        offers.updateOne(eq("_id", offerId), combine(set("fanoutDone", true), set("plannedDeliveries", planned)));
    }

    public List<FreebieOffer> stoppedAwaitingSummary(int limit) {
        return offers.find(and(eq("state", "STOPPED"), ne("summaryPosted", true))).limit(limit)
                .map(FreebieOffer::read).into(new ArrayList<>());
    }

    /** At-most-once: whoever flips the flag posts the summary. */
    public boolean claimSummary(String offerId) {
        return offers.updateOne(and(eq("_id", offerId), in("state", "COMPLETED", "STOPPED"), ne("summaryPosted", true)),
                set("summaryPosted", true)).getModifiedCount() == 1;
    }

    // ---------------------------------------------------------------- subscriptions

    public static String subscriptionId(String guildId, String channelId, FreebieStore store) {
        return guildId + ":" + channelId + ":" + store.id();
    }

    public List<Subscription> subscriptions(String guildId) {
        return subscriptions.find(eq("guildId", guildId)).sort(Sorts.ascending("_id")).limit(MAX_SUBSCRIPTIONS_PER_GUILD * 2)
                .map(FreebieRepository::subscription).into(new ArrayList<>());
    }

    public enum SaveResult { CREATED, UPDATED, LIMIT_REACHED }

    public SaveResult saveSubscription(Subscription s, String actorId, Instant now) {
        String id = subscriptionId(s.guildId(), s.channelId(), s.store());
        boolean exists = subscriptions.find(eq("_id", id)).first() != null;
        if (!exists && subscriptions.countDocuments(eq("guildId", s.guildId())) >= MAX_SUBSCRIPTIONS_PER_GUILD)
            return SaveResult.LIMIT_REACHED;
        subscriptions.replaceOne(eq("_id", id), new Document("_id", id).append("schemaVersion", 1).append("guildId", s.guildId())
                .append("channelId", s.channelId()).append("store", s.store().id()).append("roleId", s.roleId().orElse(null))
                .append("updatedBy", actorId).append("updatedAt", Date.from(now)), new ReplaceOptions().upsert(true));
        return exists ? SaveResult.UPDATED : SaveResult.CREATED;
    }

    /** Removes one launcher from a channel, or every launcher in the channel when {@code store} is empty. */
    public long removeSubscriptions(String guildId, String channelId, Optional<FreebieStore> store) {
        Bson filter = and(eq("guildId", guildId), eq("channelId", channelId));
        if (store.isPresent()) filter = and(filter, eq("store", store.get().id()));
        return subscriptions.deleteMany(filter).getDeletedCount();
    }

    /** The bot was removed from the server: its alert settings are no longer deliverable. */
    public long removeGuild(String guildId) {
        return subscriptions.deleteMany(eq("guildId", guildId)).getDeletedCount();
    }

    public Audience audience(FreebieStore store) {
        Set<String> guilds = new HashSet<>(); int channels = 0;
        for (Document d : subscriptions.find(eq("store", store.id())).projection(Projections.include("guildId"))) {
            channels++; guilds.add(d.getString("guildId"));
        }
        return new Audience(channels, guilds.size());
    }

    // ---------------------------------------------------------------- deliveries

    /** Idempotent: one delivery per offer+channel, so a crashed/retried fan-out never creates duplicates. */
    public int planDeliveries(FreebieOffer offer, Instant now) {
        int planned = 0;
        List<Document> batch = new ArrayList<>();
        for (Document s : subscriptions.find(eq("store", offer.store().id())).sort(Sorts.ascending("_id"))) {
            String channelId = s.getString("channelId");
            String id = offer.id() + "|" + channelId;
            batch.add(new Document("_id", id).append("schemaVersion", 1).append("offerId", offer.id())
                    .append("guildId", s.getString("guildId")).append("channelId", channelId).append("roleId", s.getString("roleId"))
                    .append("state", DeliveryState.PENDING.name()).append("attempts", 0).append("verifyFirst", false)
                    .append("nextAttemptAt", Date.from(now)).append("createdAt", Date.from(now)));
            if (batch.size() == 500) { planned += insertIgnoringDuplicates(batch); batch.clear(); }
        }
        if (!batch.isEmpty()) planned += insertIgnoringDuplicates(batch);
        return planned;
    }


    /** Catch-up for one channel; false when it already has (or had) a delivery for this offer. */
    public boolean planDelivery(FreebieOffer offer, Subscription s, Instant now) {
        String id = offer.id() + "|" + s.channelId();
        return insertIgnoringDuplicates(List.of(new Document("_id", id).append("schemaVersion", 1).append("offerId", offer.id())
                .append("guildId", s.guildId()).append("channelId", s.channelId()).append("roleId", s.roleId().orElse(null))
                .append("state", DeliveryState.PENDING.name()).append("attempts", 0).append("verifyFirst", false)
                .append("catchUp", true).append("nextAttemptAt", Date.from(now)).append("createdAt", Date.from(now)))) == 1;
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
                combine(set("state", "PENDING"), set("verifyFirst", true), set("nextAttemptAt", Date.from(now)),
                        unset("leaseToken"), unset("leaseUntil"))).getModifiedCount();
    }

    public Optional<Claimed> claim(Instant now, Duration lease) {
        String token = UUID.randomUUID().toString();
        var doc = deliveries.findOneAndUpdate(and(eq("state", "PENDING"), lte("nextAttemptAt", Date.from(now))),
                combine(set("state", "SENDING"), set("leaseToken", token), set("leaseUntil", Date.from(now.plus(lease))),
                        inc("attempts", 1)),
                new FindOneAndUpdateOptions().sort(Sorts.ascending("nextAttemptAt")).returnDocument(ReturnDocument.AFTER));
        if (doc == null) return Optional.empty();
        return Optional.of(new Claimed(doc.getString("_id"), doc.getString("offerId"), doc.getString("guildId"),
                doc.getString("channelId"), Optional.ofNullable(doc.getString("roleId")), doc.getInteger("attempts"),
                doc.getBoolean("verifyFirst", false), token));
    }

    private boolean finish(Claimed c, Bson... changes) {
        var all = new ArrayList<Bson>(List.of(changes));
        all.add(unset("leaseToken")); all.add(unset("leaseUntil"));
        return deliveries.updateOne(and(eq("_id", c.id()), eq("state", "SENDING"), eq("leaseToken", c.token())), combine(all))
                .getModifiedCount() == 1;
    }
    public boolean markSent(Claimed c, String messageId, Instant now) {
        return finish(c, set("state", "SENT"), set("messageId", messageId), set("finishedAt", Date.from(now)), unset("failure"));
    }
    public boolean markRetry(Claimed c, FreebieFailure failure, String detail, Instant next, boolean verifyFirst) {
        return finish(c, set("state", "PENDING"), set("nextAttemptAt", Date.from(next)), set("verifyFirst", verifyFirst),
                set("failure", failure.name()), set("failureDetail", truncate(detail)));
    }
    public boolean markFailed(Claimed c, FreebieFailure failure, String detail, Instant now) {
        return finish(c, set("state", "FAILED"), set("failure", failure.name()), set("failureDetail", truncate(detail)),
                set("finishedAt", Date.from(now)));
    }
    public boolean markCancelled(Claimed c, String reason, Instant now) {
        return finish(c, set("state", "CANCELLED"), set("failureDetail", reason), set("finishedAt", Date.from(now)));
    }
    /** Release without using up an attempt (e.g. Discord gateway temporarily disconnected). */
    public void release(Claimed c, Instant next) {
        finish(c, set("state", "PENDING"), set("nextAttemptAt", Date.from(next)), inc("attempts", -1));
    }
    public void markServerNotified(String deliveryId) {
        deliveries.updateOne(eq("_id", deliveryId), set("serverNotified", true));
    }

    public long cancelPending(String offerId, String reason, Instant now) {
        return deliveries.updateMany(and(eq("offerId", offerId), eq("state", "PENDING")),
                combine(set("state", "CANCELLED"), set("failureDetail", reason), set("finishedAt", Date.from(now)))).getModifiedCount();
    }

    public Counts counts(String offerId) {
        Map<String, Long> byState = new HashMap<>();
        for (Document d : deliveries.aggregate(List.of(Aggregates.match(eq("offerId", offerId)),
                Aggregates.group("$state", Accumulators.sum("n", 1L)))))
            byState.put(d.getString("_id"), ((Number) d.get("n")).longValue());
        return new Counts(byState.getOrDefault("SENT", 0L), byState.getOrDefault("FAILED", 0L),
                byState.getOrDefault("CANCELLED", 0L),
                byState.getOrDefault("PENDING", 0L) + byState.getOrDefault("SENDING", 0L));
    }

    public List<FailedDelivery> failures(String offerId, int limit) {
        return deliveries.find(and(eq("offerId", offerId), eq("state", "FAILED"))).sort(Sorts.ascending("_id")).limit(limit)
                .map(d -> new FailedDelivery(d.getString("guildId"), d.getString("channelId"),
                        FreebieFailure.valueOf(d.getString("failure")), d.getBoolean("serverNotified", false)))
                .into(new ArrayList<>());
    }

    // ---------------------------------------------------------------- server-owner notices

    /** At most one notice per server per day, across restarts and processes. */
    public boolean claimServerNotice(String guildId, Instant now) {
        try {
            var result = notices.updateOne(and(eq("_id", guildId), lt("lastNoticeAt", Date.from(now.minus(Duration.ofDays(1))))),
                    set("lastNoticeAt", Date.from(now)), new UpdateOptions().upsert(true));
            return result.getModifiedCount() == 1 || result.getUpsertedId() != null;
        } catch (MongoWriteException duplicate) {
            if (duplicate.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) return false;
            throw duplicate;
        }
    }

    private static Subscription subscription(Document d) {
        return new Subscription(d.getString("guildId"), d.getString("channelId"),
                FreebieStore.byId(d.getString("store")).orElse(FreebieStore.OTHER), Optional.ofNullable(d.getString("roleId")));
    }
    private static String truncate(String text) {
        return text == null ? null : text.length() <= 300 ? text : text.substring(0, 300);
    }
}
