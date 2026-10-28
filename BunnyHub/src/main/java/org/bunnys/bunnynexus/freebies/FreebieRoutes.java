package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import java.time.Instant;
import java.util.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/**
 * Delivery routes and release groups on offers and delivery rows: the one-time route decision (announcement pages or
 * the legacy per-game path), rows sharing an announcement, and pending games of the same verified Epic release.
 */
final class FreebieRoutes {
    private final MongoCollection<Document> offers, deliveries;

    FreebieRoutes(MongoCollection<Document> offers, MongoCollection<Document> deliveries) {
        this.offers = offers; this.deliveries = deliveries;
    }

    /**
     * Reserves the offer's delivery route once, before its first delivery row exists: announcement pages or the
     * per-game legacy path. {@code releaseKey} is given only by a committed release approval snapshot; every other
     * approval gets a page of its own ({@code offer:<id>}), whatever release group enrichment adds later. An offer that
     * already has rows, from before this feature, always keeps the legacy path, so historical deliveries are never
     * reopened. The first reservation is permanent.
     */
    Optional<String> route(String offerId, boolean grouped, Optional<String> releaseKey) {
        var doc = offers.find(eq("_id", offerId)).first();
        if (doc == null) return Optional.empty();
        if (doc.getString("route") == null) {
            boolean legacy = !grouped || deliveries.countDocuments(eq("offerId", offerId)) > 0;
            String key = releaseKey.orElse("offer:" + offerId);
            offers.updateOne(and(eq("_id", offerId), exists("route", false)), legacy ? set("route", "legacy")
                    : combine(set("route", "announcement"), set("announcementKey", key)));
            doc = offers.find(eq("_id", offerId)).first();
            if (doc == null) return Optional.empty();
        }
        return "announcement".equals(doc.getString("route")) ? Optional.ofNullable(doc.getString("announcementKey")) : Optional.empty();
    }

    /** Pending reviews whose saved details changed (official text, regions, mobile offers) since the review was posted. */
    List<FreebieOffer> reviewRefreshes(int limit) {
        return offers.find(and(eq("reviewRefresh", true), eq("state", "PENDING"), ne("reviewMessageId", null))).limit(limit)
                .map(FreebieOffer::read).into(new ArrayList<>());
    }

    void clearReviewRefresh(String id) { offers.updateOne(eq("_id", id), unset("reviewRefresh")); }

    /** Other offers of the same verified Epic release still awaiting review and not ended. */
    int groupPending(FreebieOffer offer, Instant now) {
        return groupMembers(offer, now).size();
    }

    /** Other pending, unended offers sharing this offer's Epic release group (section + verified start). */
    List<FreebieOffer> groupMembers(FreebieOffer offer, Instant now) {
        var doc = offers.find(eq("_id", offer.id())).projection(Projections.include("groupKey")).first();
        if (doc == null || doc.getString("groupKey") == null) return List.of();
        List<FreebieOffer> members = new ArrayList<>();
        for (Document d : offers.find(and(eq("groupKey", doc.getString("groupKey")), eq("state", "PENDING"), ne("_id", offer.id())))
                .sort(Sorts.ascending("discoveredAt", "_id")).limit(FreebieAnnouncementStore.PAGE_SIZE * FreebieAnnouncementStore.MAX_PAGES)) {
            var member = FreebieOffer.read(d);
            if (!member.ended(now)) members.add(member);
        }
        return members;
    }

    /** The saved announcement key; never assigns a route (catch-up and retries follow the original decision). */
    Optional<String> announcementKey(String offerId) {
        var doc = offers.find(eq("_id", offerId)).projection(Projections.include("route", "announcementKey")).first();
        return doc != null && "announcement".equals(doc.getString("route")) ? Optional.ofNullable(doc.getString("announcementKey")) : Optional.empty();
    }

    /** Other queued rows of the same announcement and channel, oldest first, for one shared first post. */
    List<Document> announcementPeers(String key, String channelId, String exceptOffer, int limit) {
        return deliveries.find(and(eq("announcementKey", key), eq("channelId", channelId), in("state", "PENDING", "BLOCKED"),
                ne("offerId", exceptOffer))).sort(Sorts.ascending("createdAt", "_id")).limit(limit).into(new ArrayList<>());
    }

    /** Queued member rows of a page Discord accepted: recorded sent with the page's message, without another claim. */
    long followPage(List<String> deliveryIds, String messageId, Instant now) {
        if (deliveryIds.isEmpty()) return 0;
        return deliveries.updateMany(and(in("_id", deliveryIds), in("state", "PENDING", "BLOCKED")),
                combine(set("state", "SENT"), set("messageId", messageId), set("finishedAt", Date.from(now)), set("verifyFirst", false),
                        unset("failure"), unset("blockedReason"), set("viaPage", true), inc("deliveryRevision", 1L))).getModifiedCount();
    }
}
