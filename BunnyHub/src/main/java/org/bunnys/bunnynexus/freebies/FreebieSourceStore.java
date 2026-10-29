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
    static final String LOCKS = "FreebieSourceLocks";
    private static final Duration LOCK_LEASE = Duration.ofSeconds(30);
    private final MongoCollection<Document> offers, locks;
    FreebieSourceStore(MongoCollection<Document> offers) { this(offers, null); }
    /** {@code locks}: serializes matching of new identities per launcher across processes; null in single-process tests. */
    FreebieSourceStore(MongoCollection<Document> offers, MongoCollection<Document> locks) { this.offers = offers; this.locks = locks; }
    void installIndexes() {
        offers.createIndex(Indexes.ascending("sourceAliases"), new IndexOptions().name("source_alias").unique(true).sparse(true));
        offers.createIndex(Indexes.ascending("matchKey"), new IndexOptions().name("source_match").unique(true).sparse(true));
        offers.createIndex(Indexes.ascending("productSlug", "state"), new IndexOptions().name("source_product").sparse(true));
        offers.createIndex(Indexes.ascending("store", "endsAt"), new IndexOptions().name("source_store_end"));
        offers.createIndex(Indexes.ascending("epicOffer.namespace", "epicOffer.offerId"), new IndexOptions().name("source_epic_offer").sparse(true));
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
        // GamerPower keeps its historical IDs; other providers get a short stable hash under their own prefix.
        return c.provider() == FreebieFeed.Provider.GAMERPOWER ? c.identity()
                : c.provider().id + ":" + FreebieMessages.nonce(c.identity()).substring(0, 16);
    }
    static Document fresh(FreebieFeed.Candidate c, Instant now) {
        var doc = new Document("_id", canonicalId(c)).append("schemaVersion", 1)
                .append("source", c.provider().id).append("sourceId", c.sourceId()).append("sourceAliases", List.of(c.identity()))
                .append("store", c.store().id()).append("platforms", c.platforms()).append("title", c.title())
                .append("description", c.description()).append("instructions", c.instructions()).append("worth", c.worth().orElse(null))
                .append("image", c.image().map(Object::toString).orElse(null)).append("claimUrl", c.claimPage().toString())
                .append("pageUrl", c.sourcePage().toString()).append("region", c.region())
                .append("startsAt", c.startsAt().map(Date::from).orElse(null)).append("endsAt", c.endsAt().map(Date::from).orElse(null))
                .append("state", "PENDING").append("revision", 1L).append("fanoutDone", false)
                .append("discoveredAt", Date.from(now)).append("lastSeenAt", Date.from(now)).append("missingPolls", 0)
                .append("evidence", new Document(c.provider().id, new Document("missing", 0).append("seenAt", Date.from(now)).append("identity", c.identity())));
        c.productSlug().ifPresent(slug -> doc.append("productSlug", slug));
        if (c.provider() == FreebieFeed.Provider.EPIC) {
            doc.append("official", new Document(FreebieFields.official(c)).append("observedAt", Date.from(now)));
            c.epicOffer().ifPresent(o -> doc.append("epicOffer", new Document("namespace", o.namespace()).append("offerId", o.offerId())));
            FreebieFields.groupKey(c.store(), c.startsAt()).ifPresent(key -> doc.append("groupKey", key));
        } else doc.append("reported", new Document(c.provider().id, new Document("platforms", c.platforms())
                .append("observedAt", Date.from(now)).append("identity", c.identity())));
        return doc;
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
        if (locks == null || offers.countDocuments(eq("sourceAliases", c.identity())) > 0) return recordUnlocked(c, now);
        // A new identity: matching against other providers and inserting must not interleave with another new report
        // of the same launcher, or two providers arriving at once could each open a review.
        String lock = "store|" + c.store().id(), token = UUID.randomUUID().toString();
        for (int attempt = 0; attempt < 100; attempt++) {
            if (acquire(lock, token)) {
                try { return recordUnlocked(c, now); }
                finally { locks.deleteOne(and(eq("_id", lock), eq("token", token))); }
            }
            try { Thread.sleep(20); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        throw new IllegalStateException("Source identity contention; retry later");
    }

    /** Wall-clock lease, so a crashed holder blocks new identities of one launcher for at most 30 seconds. */
    private boolean acquire(String lock, String token) {
        Instant wall = Instant.now();
        try {
            return locks.findOneAndUpdate(and(eq("_id", lock), or(exists("until", false), lte("until", Date.from(wall)))),
                    combine(set("token", token), set("until", Date.from(wall.plus(LOCK_LEASE)))),
                    new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER)) != null;
        } catch (com.mongodb.MongoServerException held) {
            // findAndModify reports the collision as a command error, not a write error.
            if (ErrorCategory.fromErrorCode(held.getCode()) != ErrorCategory.DUPLICATE_KEY) throw held;
            return false; // Another holder's live lease: the upsert collided with its document.
        }
    }

    private boolean recordUnlocked(FreebieFeed.Candidate c, Instant now) {
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
                List<org.bson.conversions.Bson> seen = new ArrayList<>(List.of(
                        set("lastSeenAt", Date.from(now)), set("missingPolls", 0), set("gone", false),
                        set("evidence." + c.provider().id, new Document("missing", 0).append("seenAt", Date.from(now)).append("identity", c.identity()))));
                // Offers stored before a source reported a price get it the next time they are seen, while still in
                // review: approved cards keep exactly what the owner approved.
                if (c.worth().isPresent() && existing.getString("worth") == null && "PENDING".equals(existing.getString("state")))
                    offers.updateOne(and(eq("_id", existing.getString("_id")), eq("worth", null), eq("state", "PENDING")),
                            set("worth", c.worth().get()));
                seen.addAll(FreebieFields.changes(existing, c, now));
                // Conditional on the state read, so rules chosen for a pending review never land on an approved offer.
                var applied = offers.updateOne(and(eq("_id", existing.getString("_id")), eq("state", existing.getString("state"))), combine(seen));
                if (applied.getMatchedCount() == 0) continue;
                return false;
            }
            var verified = verifiedMatch(c, now);
            if (verified.isPresent()) {
                Match match = verified.get();
                // Ambiguous repeat giveaways and edition/add-on differences: an owner decides; nothing joins automatically.
                boolean owner = match.ambiguous() || FreebieIdentity.editionConflict(match.row().getString("title"), c.title());
                if (owner ? reserve(match.row(), c, fresh) : join(match.row(), c, now)) return false;
                continue;
            }
            Document possible = key == null ? null : offers.find(eq("matchKey", key)).first();
            if (possible == null) possible = relatedTitle(c, now).orElse(null);
            if (possible != null) {
                if (reserve(possible, c, fresh)) return false; // No new review or liveness authority until the owner resolves it.
            } else {
                if (key != null) fresh.put("matchKey", key);
                try { offers.insertOne(fresh); return true; }
                catch (MongoWriteException race) { if (race.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) throw race; }
            }
        }
        throw new IllegalStateException("Source identity contention; retry later");
    }
    record Match(Document row, boolean ambiguous) {}

    /**
     * The saved giveaway this report verifiably describes: the same normalized Epic product page, the same launcher
     * and an overlapping promotion window. Several plausible occurrences return the closest as ambiguous.
     */
    private Optional<Match> verifiedMatch(FreebieFeed.Candidate c, Instant now) {
        var slug = c.productSlug();
        if (slug.isEmpty()) return Optional.empty();
        List<Document> rows = new ArrayList<>();
        for (Document d : offers.find(and(eq("productSlug", slug.get()), eq("store", c.store().id()), ne("state", "PURGED"),
                ne("sourceAliases", c.identity()))).limit(20))
            if (FreebieIdentity.sameOccurrence(d, c, now)) rows.add(d);
        return FreebieIdentity.closest(rows, c).map(row -> new Match(row, rows.size() > 1));
    }

    /** Related titles (equal, or one plus a subtitle) from another provider with a close end: an owner question only. */
    private Optional<Document> relatedTitle(FreebieFeed.Candidate c, Instant now) {
        var end = FreebieIdentity.comparableEnd(c);
        // Without an end date ("N/A"), any live giveaway of another provider with a related title is a candidate:
        // a missing date must not let the report skip the owner question and open a second review.
        var dates = end.isEmpty() ? or(eq("endsAt", null), gt("endsAt", Date.from(now))) : and(
                gte("endsAt", Date.from(end.get().minus(FreebieIdentity.END_TOLERANCE).minusSeconds(7200))),
                lte("endsAt", Date.from(end.get().plus(FreebieIdentity.END_TOLERANCE).plusSeconds(7200))));
        var window = and(eq("store", c.store().id()), ne("source", c.provider().id), in("state", "PENDING", "APPROVED", "COMPLETED"), dates);
        List<Document> rows = new ArrayList<>();
        for (Document d : offers.find(window).limit(50))
            if (FreebieIdentity.titlesRelated(d.getString("title"), c.title()) && FreebieIdentity.sameOccurrence(d, c, now)) rows.add(d);
        return FreebieIdentity.closest(rows, c);
    }

    /** Reserves the identity and its immutable snapshot on {@code target} for an owner decision. False on a lost race. */
    private boolean reserve(Document target, FreebieFeed.Candidate c, Document fresh) {
        if (target.getList("sourceAliases", String.class, List.of()).size() >= 64)
            throw new IllegalStateException("Source identity reservation needs owner maintenance");
        String token = FreebieMessages.reference(c.identity());
        try {
            return offers.updateOne(and(eq("_id", target.getString("_id")), ne("sourceAliases", c.identity()), ne("state", "PURGED"),
                    new Document("$expr", new Document("$lt", List.of(new Document("$size", "$sourceAliases"), 64)))),
                    combine(addToSet("sourceAliases", c.identity()), set("sourceCandidates." + token, fresh),
                            addToSet("sourceQuestionsPending", token))).getMatchedCount() == 1;
        } catch (MongoWriteException race) {
            if (race.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) throw race;
            return false;
        }
    }

    /**
     * Verified storefront identity: the alias joins the existing giveaway with its own liveness evidence and the field
     * rules of {@link FreebieFields}, in one conditional write. No review, approval or send is created.
     */
    private boolean join(Document target, FreebieFeed.Candidate c, Instant now) {
        List<org.bson.conversions.Bson> changes = new ArrayList<>(List.of(addToSet("sourceAliases", c.identity()),
                set("evidence." + c.provider().id, new Document("missing", 0).append("seenAt", Date.from(now)).append("identity", c.identity())),
                set("lastSeenAt", Date.from(now)), set("sourceJoins." + FreebieMessages.reference(c.identity()),
                        new Document("identity", c.identity()).append("at", Date.from(now)).append("rule", "product-page"))));
        changes.addAll(FreebieFields.changes(target, c, now));
        try {
            return offers.updateOne(and(eq("_id", target.getString("_id")), eq("state", target.getString("state")),
                    ne("sourceAliases", c.identity()),
                    new Document("$expr", new Document("$lt", List.of(new Document("$size", "$sourceAliases"), 64)))),
                    combine(changes)).getMatchedCount() == 1;
        } catch (MongoWriteException race) {
            if (race.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) throw race;
            return false;
        }
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
        List<org.bson.conversions.Bson> changes = new ArrayList<>(List.of(unset("sourceCandidates." + token), pull("sourceQuestionsPending", token),
                set("sourceDecisions." + token, decision), set("sourceActors." + token, actor), set("sourceDecidedAt." + token, Date.from(now))));
        // Same/ignore decisions do not change evidence; only the next live observation can do that.
        if (decision.equals("separate")) changes.add(set("separateCandidates." + token, candidate));
        return offers.updateOne(and(eq("_id", id), eq("sourceCandidates." + token, candidate)), combine(changes)).getModifiedCount() == 1;
    }
    /** A held report not yet announced to the owners: the giveaway it is held on and its question token. */
    record Question(Document parent, String token) {}

    /** Held reports to announce in the review channel, at most {@code limit}. */
    List<Question> unannounced(int limit) {
        List<Question> result = new ArrayList<>();
        for (Document d : offers.find(and(exists("sourceQuestionsPending.0"), ne("state", "PURGED"))).limit(limit))
            for (String token : d.getList("sourceQuestionsPending", String.class, List.of()))
                if (d.get("sourceCandidates", Document.class) != null && d.get("sourceCandidates", Document.class).containsKey(token))
                    result.add(new Question(d, token));
                else offers.updateOne(eq("_id", d.getString("_id")), pull("sourceQuestionsPending", token)); // Already resolved.
        return result;
    }

    /** The question was posted with its buttons; it stays open (and counted) until an owner decides. */
    void announced(Question question) {
        offers.updateOne(eq("_id", question.parent().getString("_id")), pull("sourceQuestionsPending", question.token()));
    }

    /** Held reports still waiting for an owner decision. */
    long openQuestions() {
        long open = 0;
        for (Document d : offers.find(and(exists("sourceCandidates"), ne("sourceCandidates", new Document()), ne("state", "PURGED")))
                .projection(com.mongodb.client.model.Projections.include("sourceCandidates")).limit(500))
            open += d.get("sourceCandidates", Document.class).size();
        return open;
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




