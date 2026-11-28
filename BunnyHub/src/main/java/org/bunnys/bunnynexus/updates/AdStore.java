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
                .append("buttons", ad.links().stream().map(l -> new Document("label", l.label()).append("url", l.url().toString())
                        .append("emoji", l.emoji().orElse(null))).toList())
                .append("emoji", ad.emoji().orElse(null)).append("when", ad.when().name()).append("priority", ad.priority())
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

    /** The starter ads added before versions were kept: version 1 of each, already in the database. */
    static final Set<String> FIRST_STARTERS = Set.of("get-bunnynexus", "deals-board");

    /**
     * Adds each starter ad at its version: a new one is added, a newer version replaces the saved ad (keeping whether it
     * is paused), and one the owner deleted at its current version stays deleted. Returns how many were written.
     */
    public int seed(Map<Ad, Integer> starters) {
        Document marker = ads.find(eq("_id", SEEDED)).first();
        boolean fresh = marker == null;
        Document versions = marker == null ? new Document() : Objects.requireNonNullElse(marker.get("versions", Document.class), new Document());
        if (!fresh && !marker.containsKey("versions")) FIRST_STARTERS.forEach(id -> versions.put(id, 1));
        int written = 0;
        for (var entry : starters.entrySet()) {
            Ad ad = entry.getKey();
            int version = entry.getValue();
            if (versions.getInteger(ad.id(), 0) >= version) continue;
            boolean on = get(ad.id()).map(Ad::on).orElse(ad.on());
            save(ad.withOn(on));
            versions.put(ad.id(), version);
            written++;
        }
        ads.updateOne(eq("_id", SEEDED), Updates.combine(Updates.set("versions", versions), Updates.setOnInsert("at", new Date())),
                new com.mongodb.client.model.UpdateOptions().upsert(true));
        return written;
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
            List<Ad.Link> links = new ArrayList<>();
            if (doc.containsKey("buttons")) {
                for (Document b : doc.getList("buttons", Document.class))
                    links.add(new Ad.Link(b.getString("label"), b.getString("url"), Optional.ofNullable(b.getString("emoji"))));
            } else {
                // Saved before buttons were a list: at most one, without an emoji.
                String label = doc.getString("linkLabel"), url = doc.getString("linkUrl");
                if (label != null && url != null) links.add(new Ad.Link(label, URI.create(url)));
            }
            Date created = doc.getDate("createdAt");
            return Optional.of(new Ad(doc.getString("_id"), Optional.ofNullable(doc.getString("title")), doc.getString("text"),
                    Optional.ofNullable(doc.getString("image")).map(URI::create), links,
                    Ad.Audience.valueOf(doc.getString("audience")), Boolean.TRUE.equals(doc.getBoolean("on")),
                    created == null ? Instant.EPOCH : created.toInstant(), Optional.ofNullable(doc.getString("emoji")),
                    doc.getString("when") == null ? Ad.When.ANY : Ad.When.valueOf(doc.getString("when")), doc.getInteger("priority", 0)));
        } catch (RuntimeException unreadable) { return Optional.empty(); }
    }
}
