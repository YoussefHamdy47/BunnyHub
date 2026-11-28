package org.bunnys.bunnynexus.updates;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;

import java.time.Instant;
import java.util.*;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;

/**
 * Each server's product-update choice and history ({@code ProductUpdatePrefs}, docs/product-updates/README.md):
 * {@code {_id: guildId, off, offBy, offAt, lastSentAt, sent: [updateId]}}. Blocking; never on the gateway thread.
 */
public final class UpdatePrefs {
    public static final String COLLECTION = "ProductUpdatePrefs";
    private final MongoCollection<Document> prefs;

    public UpdatePrefs(MongoDatabase db) { prefs = db.getCollection(COLLECTION); }

    /** The same records in another collection: {@link ProductUpdates#USER_COLLECTION} keeps each person's ad history by user id. */
    UpdatePrefs(MongoDatabase db, String collection) { prefs = db.getCollection(collection); }

    public UpdateAudience.Prefs get(String guildId) {
        return read(prefs.find(eq("_id", guildId)).first());
    }

    /** Preferences for many servers in one query; a server without a document gets {@link UpdateAudience.Prefs#NONE}. */
    public Map<String, UpdateAudience.Prefs> get(Collection<String> guildIds) {
        Map<String, UpdateAudience.Prefs> result = new HashMap<>();
        for (Document doc : prefs.find(in("_id", guildIds))) result.put(doc.getString("_id"), read(doc));
        for (String id : guildIds) result.putIfAbsent(id, UpdateAudience.Prefs.NONE);
        return result;
    }

    /** An admin turned product updates off ({@code off}) or back on. */
    public void setOff(String guildId, boolean off, String userId, Instant now) {
        prefs.updateOne(eq("_id", guildId), Updates.combine(Updates.set("off", off), Updates.set("offBy", userId),
                Updates.set("offAt", Date.from(now))), new UpdateOptions().upsert(true));
    }

    /** {@code updateId} was delivered to this server. */
    public void sent(String guildId, String updateId, Instant now) {
        prefs.updateOne(eq("_id", guildId), Updates.combine(Updates.addToSet("sent", updateId), Updates.set("lastSentAt", Date.from(now))),
                new UpdateOptions().upsert(true));
    }

    public UpdateAudience.PromoState promoState(String guildId) {
        Document doc = prefs.find(eq("_id", guildId)).first();
        if (doc == null) return UpdateAudience.PromoState.NONE;
        Map<String, Integer> shown = new HashMap<>();
        if (doc.get("promos") instanceof Document promos)
            promos.forEach((id, count) -> { if (count instanceof Number n) shown.put(id, n.intValue()); });
        return new UpdateAudience.PromoState(Optional.ofNullable(doc.getDate("lastPromoAt")).map(Date::toInstant), shown);
    }

    /** {@code updateId}'s promo went under one of this server's alerts. */
    public void promoShown(String guildId, String updateId, Instant now) {
        prefs.updateOne(eq("_id", guildId), Updates.combine(Updates.inc("promos." + updateId, 1), Updates.set("lastPromoAt", Date.from(now))),
                new UpdateOptions().upsert(true));
    }

    /**
     * Counts {@code adId} as shown only if the server is still due one (no ad since {@code every}, this ad under
     * {@code times}). Atomic, so two alerts built at once for the same server never both carry an ad.
     */
    public boolean claimPromo(String guildId, String adId, Instant now, java.time.Duration every, int times) {
        var due = com.mongodb.client.model.Filters.and(eq("_id", guildId),
                com.mongodb.client.model.Filters.or(com.mongodb.client.model.Filters.exists("lastPromoAt", false),
                        com.mongodb.client.model.Filters.lte("lastPromoAt", Date.from(now.minus(every)))),
                com.mongodb.client.model.Filters.or(com.mongodb.client.model.Filters.exists("promos." + adId, false),
                        com.mongodb.client.model.Filters.lt("promos." + adId, times)));
        try {
            var result = prefs.updateOne(due, Updates.combine(Updates.inc("promos." + adId, 1), Updates.set("lastPromoAt", Date.from(now))),
                    new UpdateOptions().upsert(true));
            return result.getModifiedCount() > 0 || result.getUpsertedId() != null;
        } catch (com.mongodb.MongoWriteException taken) {
            // The document exists but is not due: the upsert's insert hit the same _id. Someone else showed one.
            if (taken.getError().getCategory() == com.mongodb.ErrorCategory.DUPLICATE_KEY) return false;
            throw taken;
        }
    }

    /** The bot left the server. */
    public void forget(String guildId) { prefs.deleteOne(eq("_id", guildId)); }

    private static UpdateAudience.Prefs read(Document doc) {
        if (doc == null) return UpdateAudience.Prefs.NONE;
        Date last = doc.getDate("lastSentAt");
        return new UpdateAudience.Prefs(Boolean.TRUE.equals(doc.getBoolean("off")), Optional.ofNullable(last).map(Date::toInstant),
                new HashSet<>(doc.getList("sent", String.class, List.of())));
    }
}
