package org.bunnys.bunnynexus.updates;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import org.bson.Document;

import java.net.URI;
import java.time.Instant;
import java.util.*;

import static com.mongodb.client.model.Filters.eq;

/**
 * The owner's ads ({@code FreebieAds}): one document per ad. Few and small, so the active ones are cached for a
 * minute instead of read on every alert. Blocking; never on the gateway thread.
 */
public final class AdStore {
    public static final String COLLECTION = "FreebieAds";
    static final long CACHE_MILLIS = 60_000;
    private final MongoCollection<Document> ads;
    private volatile List<Ad> active;
    private volatile long activeAt;

    public AdStore(MongoDatabase db) { ads = db.getCollection(COLLECTION); }

    /** Adds the ad, or replaces the one with the same id (its creation time is kept). */
    public void save(Ad ad) {
        var doc = new Document("_id", ad.id()).append("title", ad.title().orElse(null)).append("text", ad.text())
                .append("image", ad.image().map(URI::toString).orElse(null))
                .append("linkLabel", ad.link().map(Ad.Link::label).orElse(null))
                .append("linkUrl", ad.link().map(l -> l.url().toString()).orElse(null))
                .append("audience", ad.audience().name()).append("on", ad.on())
                .append("createdAt", Date.from(get(ad.id()).map(Ad::createdAt).orElse(ad.createdAt())));
        ads.replaceOne(eq("_id", ad.id()), doc, new ReplaceOptions().upsert(true));
        active = null;
    }

    public Optional<Ad> get(String id) { return Optional.ofNullable(ads.find(eq("_id", id)).first()).flatMap(AdStore::read); }

    /** Every ad, newest first. */
    public List<Ad> all() {
        List<Ad> list = new ArrayList<>();
        for (Document doc : ads.find().sort(Sorts.descending("createdAt")).limit(100)) read(doc).ifPresent(list::add);
        return list;
    }

    /** The ads that are on, newest first; cached for a minute. */
    public List<Ad> active() {
        var cached = active;
        if (cached != null && System.currentTimeMillis() - activeAt < CACHE_MILLIS) return cached;
        cached = all().stream().filter(Ad::on).toList();
        active = cached;
        activeAt = System.currentTimeMillis();
        return cached;
    }

    /** Marks that the starter ads were added once, so deleting them later never brings them back. Not an ad (read skips it). */
    static final String SEEDED = "_seeded";

    /** Adds the starter ads the first time ads start in this database. True when it added them. */
    public boolean seedOnce(List<Ad> starters) {
        var marker = ads.updateOne(eq("_id", SEEDED), Updates.setOnInsert("at", new Date()), new com.mongodb.client.model.UpdateOptions().upsert(true));
        if (marker.getUpsertedId() == null) return false;
        starters.forEach(this::save);
        return true;
    }

    public boolean setOn(String id, boolean on) {
        boolean found = ads.updateOne(eq("_id", id), Updates.set("on", on)).getMatchedCount() > 0;
        active = null;
        return found;
    }

    public boolean remove(String id) {
        boolean found = ads.deleteOne(eq("_id", id)).getDeletedCount() > 0;
        active = null;
        return found;
    }

    private static Optional<Ad> read(Document doc) {
        try {
            String label = doc.getString("linkLabel"), url = doc.getString("linkUrl");
            Optional<Ad.Link> link = label != null && url != null ? Optional.of(new Ad.Link(label, URI.create(url))) : Optional.empty();
            Date created = doc.getDate("createdAt");
            return Optional.of(new Ad(doc.getString("_id"), Optional.ofNullable(doc.getString("title")), doc.getString("text"),
                    Optional.ofNullable(doc.getString("image")).map(URI::create), link,
                    Ad.Audience.valueOf(doc.getString("audience")), Boolean.TRUE.equals(doc.getBoolean("on")),
                    created == null ? Instant.EPOCH : created.toInstant()));
        } catch (RuntimeException unreadable) { return Optional.empty(); }
    }
}
