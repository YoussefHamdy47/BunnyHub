package org.bunnys.bunnynexus.freebies;

import com.mongodb.MongoWriteException;
import com.mongodb.ErrorCategory;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.*;
import org.bson.Document;
import java.time.*;
import java.text.Normalizer;
import java.util.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/** Durable identity reservations and source evidence on the canonical offer. Titles only propose an owner decision. */
final class FreebieSourceStore {
    private final MongoCollection<Document> offers;
    FreebieSourceStore(MongoCollection<Document> offers) { this.offers = offers; }
    void installIndexes() {
        offers.createIndex(Indexes.ascending("sourceAliases"), new IndexOptions().name("source_alias").unique(true).sparse(true));
        offers.createIndex(Indexes.ascending("matchKey"), new IndexOptions().name("source_match").unique(true).sparse(true));
    }
    /** Bounded and restartable. New-source writes wait until every historical row has its alias reservation. */
    boolean backfill() {
        for (Document d : offers.find(exists("sourceAliases", false)).limit(200)) {
            String id = d.getString("_id");
            String alias = d.getString("source") + ":" + d.getString("sourceId");
            if (id.startsWith("gamerpower:")) alias = id;
            var changes = new ArrayList<org.bson.conversions.Bson>();
            changes.add(set("sourceAliases", List.of(alias)));
            if (!"PURGED".equals(d.getString("state"))) {
                changes.add(set("evidence." + d.getString("source"), new Document("missing", d.getInteger("missingPolls", 0))
                        .append("seenAt", d.getDate("lastSeenAt")).append("identity", alias)));
                String key = matchKey(d);
                if (key != null && offers.countDocuments(eq("matchKey", key)) == 0) changes.add(set("matchKey", key));
            }
            try { offers.updateOne(and(eq("_id", id), exists("sourceAliases", false)), combine(changes)); }
            catch (MongoWriteException race) { if (race.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) throw race; }
        }
        return offers.countDocuments(exists("sourceAliases", false)) == 0;
    }
    static String canonicalId(FreebieFeed.Candidate c) {
        return c.provider() == FreebieFeed.Provider.GAMERPOWER ? c.identity() : "epic:" + FreebieMessages.nonce(c.identity()).substring(0, 16);
    }
    static Document fresh(FreebieFeed.Candidate c, Instant now) {
        return new Document("_id", canonicalId(c)).append("schemaVersion", 1)
                .append("source", c.provider().id).append("sourceId", c.sourceId()).append("sourceAliases", List.of(c.identity()))
                .append("store", c.store().id()).append("platforms", c.platforms()).append("title", c.title())
                .append("description", c.description()).append("instructions", c.instructions()).append("worth", c.worth().orElse(null))
                .append("image", c.image().map(Object::toString).orElse(null)).append("claimUrl", c.claimPage().toString())
                .append("pageUrl", c.sourcePage().toString()).append("region", c.region())
                .append("startsAt", c.startsAt().map(Date::from).orElse(null)).append("endsAt", c.endsAt().map(Date::from).orElse(null))
                .append("state", "PENDING").append("revision", 1L).append("fanoutDone", false)
                .append("discoveredAt", Date.from(now)).append("lastSeenAt", Date.from(now)).append("missingPolls", 0)
                .append("evidence", new Document(c.provider().id, new Document("missing", 0).append("seenAt", Date.from(now)).append("identity", c.identity())));
    }
    static String matchKey(Document d) {
        if (d.getString("title") == null || d.getDate("endsAt") == null) return null;
        String title = FreebieMessages.title(d.getString("title"));
        title = title.replaceFirst("(?i)\\s*\\((?:Epic Games(?: Store)?|Steam|GOG)\\)$", "").strip();
        title = Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
        // Do not discard edition words, punctuation or accents. Day precision only proposes a match.
        Instant end = d.getDate("endsAt").toInstant();
        if ("gamerpower".equals(d.getString("source"))) end = end.minus(Duration.ofHours(2));
        return d.getString("store") + "|" + title + "|" + end.atOffset(ZoneOffset.UTC).toLocalDate();
    }
    boolean record(FreebieFeed.Candidate c, Instant now) {
        if (!backfill()) throw new IllegalStateException("Source identity migration is still running");
        Document fresh = fresh(c, now); String key = matchKey(fresh);
        for (int attempt = 0; attempt < 8; attempt++) {
            Document existing = offers.find(eq("sourceAliases", c.identity())).first();
            if (existing != null) {
                String token = FreebieMessages.reference(c.identity());
                Document decisions = existing.get("sourceDecisions", Document.class);
                String decision = decisions == null ? null : decisions.getString(token);
                if ("separate".equals(decision)) return materialize(existing, token, now);
                Document pending = existing.get("sourceCandidates", Document.class);
                if ("ignore".equals(decision) || pending != null && pending.containsKey(token)) return false;
                if ("PURGED".equals(existing.getString("state"))) return false;
                offers.updateOne(and(eq("_id", existing.getString("_id")), ne("state", "PURGED")), combine(
                        set("lastSeenAt", Date.from(now)), set("missingPolls", 0), set("gone", false),
                        set("evidence." + c.provider().id, new Document("missing", 0).append("seenAt", Date.from(now)).append("identity", c.identity()))));
                return false;
            }
            Document possible = key == null ? null : offers.find(eq("matchKey", key)).first();
            if (possible != null) {
                if (possible.getList("sourceAliases", String.class, List.of()).size() >= 64)
                    throw new IllegalStateException("Source identity reservation needs owner maintenance");
                String token = FreebieMessages.reference(c.identity());
                try {
                    var reserved = offers.updateOne(and(eq("_id", possible.getString("_id")), ne("sourceAliases", c.identity()),
                            new Document("$expr", new Document("$lt", List.of(new Document("$size", "$sourceAliases"), 64)))), combine(addToSet("sourceAliases", c.identity()),
                            set("sourceCandidates." + token, fresh)));
                    if (reserved.getMatchedCount() == 0) continue;
                    return false; // Reserved, but no new review or liveness authority until the owner resolves it.
                } catch (MongoWriteException race) { if (race.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) throw race; }
            } else {
                if (key != null) fresh.put("matchKey", key);
                try { offers.insertOne(fresh); return true; }
                catch (MongoWriteException race) { if (race.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) throw race; }
            }
        }
        throw new IllegalStateException("Source identity contention; retry later");
    }
    private boolean materialize(Document parent, String token, Instant now) {
        Document saved = parent.get("separateCandidates", Document.class).get(token, Document.class);
        Document child = new Document(saved);
        String id = child.getString("_id"); child.remove("_id"); child.remove("matchKey");
        child.put("sourceAliases", List.of("separate:" + id)); // The parent permanently owns and routes the provider alias.
        child.put("sourceParent", parent.getString("_id"));
        var result = offers.updateOne(eq("_id", id), new Document("$setOnInsert", child), new UpdateOptions().upsert(true));
        Document evidence = new Document(saved.get("evidence", Document.class).get(child.getString("source"), Document.class));
        evidence.put("seenAt", Date.from(now));
        offers.updateOne(and(eq("_id", id), ne("state", "PURGED")), combine(set("lastSeenAt", Date.from(now)),
                set("gone", false), set("evidence." + child.getString("source"), evidence)));
        return result.getUpsertedId() != null;
    }
    Optional<Document> conflict() {
        return Optional.ofNullable(offers.find(new Document("sourceCandidates", new Document("$exists", true)
                .append("$ne", new Document()))).sort(Sorts.ascending("_id")).first());
    }
    boolean resolve(String id, String token, String decision, String actor, Instant now) {
        if (!List.of("same", "ignore", "separate").contains(decision)) return false;
        Document parent = offers.find(and(eq("_id", id), exists("sourceCandidates." + token))).first();
        if (parent == null) return false;
        Document candidate = parent.get("sourceCandidates", Document.class).get(token, Document.class);
        List<org.bson.conversions.Bson> changes = new ArrayList<>(List.of(unset("sourceCandidates." + token),
                set("sourceDecisions." + token, decision), set("sourceActors." + token, actor), set("sourceDecidedAt." + token, Date.from(now))));
        // Same/ignore decisions do not change evidence; only the next live observation can do that.
        if (decision.equals("separate")) changes.add(set("separateCandidates." + token, candidate));
        return offers.updateOne(and(eq("_id", id), eq("sourceCandidates." + token, candidate)), combine(changes)).getModifiedCount() == 1;
    }
    List<FreebieOffer> missing(FreebieFeed.Provider provider, Set<String> present) {
        String field = "evidence." + provider.id + ".missing";
        var filter = and(in("state", "PENDING", "APPROVED", "COMPLETED"), exists("evidence." + provider.id),
                nin("evidence." + provider.id + ".identity", present), nin("_id", present), ne("gone", true));
        offers.updateMany(filter, inc(field, 1));
        List<FreebieOffer> ended = new ArrayList<>();
        for (Document d : offers.find(and(filter, gte(field, 2))).limit(200)) {
            Document evidence = d.get("evidence", Document.class);
            boolean absent = evidence.values().stream().allMatch(v -> ((Document) v).getInteger("missing", 0) >= 2);
            if (!absent) continue;
            Document changed = offers.findOneAndUpdate(and(eq("_id", d.getString("_id")), eq("evidence", evidence), ne("gone", true)),
                    set("gone", true), new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            if (changed != null) ended.add(FreebieOffer.read(changed));
        }
        return ended;
    }
}




