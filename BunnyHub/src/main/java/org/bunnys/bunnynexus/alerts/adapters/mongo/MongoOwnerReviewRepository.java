package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.client.ClientSession;
import com.mongodb.client.model.*;
import org.bson.Document;
import org.bunnys.bunnynexus.alerts.application.*;
import org.bunnys.bunnynexus.alerts.domain.*;
import java.time.*;
import java.util.*;
import static com.mongodb.client.model.Filters.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoOwnerReviewSchema.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertSchema.SIMPLE;

/** Durable owner-only two-step release. No constructor provisions data or publishes messages. */
public final class MongoOwnerReviewRepository implements OwnerReviewRepository {
    private static final String TEMPLATE = org.bunnys.bunnynexus.alerts.adapters.discord.AlertMessageRenderer.TEMPLATE;
    public static final int MAX_ATTEMPTS = 10_000;
    public static final Duration RELEASE_LIFETIME = Duration.ofHours(1);
    private final MongoAlertDatabase db;
    private final String ownerId;
    private final FreeGamePolicy policy;
    public MongoOwnerReviewRepository(MongoAlertDatabase db, String ownerId, FreeGamePolicy policy) {
        this.db = Objects.requireNonNull(db); this.ownerId = AlertIdentity.snowflake(ownerId); this.policy = Objects.requireNonNull(policy);
        if (policy.allowUnknownEnd()) throw new IllegalArgumentException("Known end required for owner release.");
    }
    private void owner(String actor) {
        if (!ownerId.equals(AlertIdentity.snowflake(actor))) throw new SecurityException("Bot owner required.");
    }
    @Override public Optional<Snapshot> get(String actor, AlertIdentity.OfferKey key) {
        owner(actor);
        return Optional.ofNullable(db.collection(REVIEWS).find(and(eq("_id", CatalogDocuments.offerId(key)), eq("ownerId", ownerId)))
                .collation(SIMPLE).first()).map(OwnerReviewDocuments::read);
    }
    @Override public Result prepare(String actor, String action, long expected, Offer offer, AlertDisplayContent content) {
        owner(actor); String hash = PublicationReviewPolicy.materialHash(offer, content);
        return change(actor, action, offer.key(), expected, "PREPARE", 0, offer, content, hash);
    }
    @Override public Result verify(String actor, String action, AlertIdentity.OfferKey key, long expected) {
        return change(actor, action, key, expected, "VERIFY", 0, null, null, "none");
    }
    @Override public Result release(String actor, String action, AlertIdentity.OfferKey key, long expected, int maximum) {
        if (maximum < 1 || maximum > MAX_ATTEMPTS) throw new IllegalArgumentException("Explicit bounded send-attempt limit required.");
        return change(actor, action, key, expected, "RELEASE", maximum, null, null, "none");
    }
    @Override public Result revoke(String actor, String action, AlertIdentity.OfferKey key, long expected) {
        return change(actor, action, key, expected, "REVOKE", 0, null, null, "none");
    }
    private Result change(String actor, String action, AlertIdentity.OfferKey key, long expected, String operation,
                          int maximum, Offer offer, AlertDisplayContent content, String hash) {
        owner(actor); AlertIdentity.snowflake(action);
        if (expected < 0) throw new IllegalArgumentException("Expected revision required.");
        String id = CatalogDocuments.offerId(key);
        String fingerprint = StableIdentity.hash("owner-review-action-v1", actor, id, operation, Long.toString(expected),
                Integer.toString(maximum), hash, offer == null ? "none" : offer.verifiedAt().toString(),
                offer == null ? "none" : Long.toString(offer.contentRevision()), TEMPLATE);
        try { return db.transaction(session -> {
            var receipt = db.collection(AUDIT).find(session, eq("_id", action)).collation(SIMPLE).first();
            if (receipt != null) {
                AlertDocuments.version(receipt);
                return new Result(fingerprint.equals(receipt.getString("fingerprint")) ? Status.REPLAYED : Status.CONFLICT,
                        AlertDocuments.number(receipt, "revision"));
            }
            var raw = db.collection(REVIEWS).findOneAndUpdate(session, eq("_id", id),
                    new Document("$setOnInsert", new Document("schemaVersion", 1).append("ownerId", ownerId).append("revision", 0L))
                            .append("$currentDate", new Document("checkedAt", true)),
                    new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER).collation(SIMPLE));
            AlertDocuments.version(raw);
            long revision = AlertDocuments.number(raw, "revision");
            if (!ownerId.equals(raw.getString("ownerId")) || revision != expected) throw new Conflict(revision);
            Instant now = AlertDocuments.instant(raw, "checkedAt");
            Document next = new Document(raw);
            if (operation.equals("PREPARE")) {
                next.putAll(new Document("state", State.PENDING.name()).append("offer", CatalogDocuments.offer(offer))
                        .append("content", OwnerReviewDocuments.content(content)).append("materialHash", hash).append("templateVersion", TEMPLATE)
                        .append("previewUntil", AlertDocuments.date(now.plus(PublicationReviewPolicy.PREVIEW_LIFETIME)))
                        .append("maximumAttempts", 0).append("usedAttempts", 0));
                next.remove("releaseUntil"); next.remove("releasedAt"); next.remove("verifiedAt");
            } else {
                if (revision == 0) throw new Conflict(0);
                var current = OwnerReviewDocuments.read(raw);
                if (!operation.equals("REVOKE") && (!now.isBefore(current.previewUntil()) || !policy.evaluate(current.offer(), now).eligible()))
                    throw new IllegalStateException("Preview expired or offer evidence needs refresh.");
                switch (operation) {
                    case "VERIFY" -> {
                        if (current.state() != State.PENDING) throw new Conflict(revision);
                        next.put("state", State.VERIFIED.name()); next.put("verifiedAt", AlertDocuments.date(now));
                    }
                    case "RELEASE" -> {
                        if (current.state() != State.VERIFIED) throw new Conflict(revision);
                        Instant until = current.offer().endsAt().orElseThrow();
                        if (now.plus(RELEASE_LIFETIME).isBefore(until)) until = now.plus(RELEASE_LIFETIME);
                        next.putAll(new Document("state", State.RELEASED.name()).append("releasedAt", AlertDocuments.date(now))
                                .append("releaseUntil", AlertDocuments.date(until)).append("maximumAttempts", maximum).append("usedAttempts", 0));
                    }
                    case "REVOKE" -> next.put("state", State.REVOKED.name());
                    default -> throw new IllegalArgumentException("Unsupported owner operation.");
                }
            }
            next.put("revision", Math.incrementExact(revision));
            OwnerReviewDocuments.read(next);
            var filters = new ArrayList<org.bson.conversions.Bson>(List.of(eq("_id", id), eq("ownerId", ownerId), eq("revision", revision),
                    new Document("$expr", new Document("$gte", List.of("$$NOW", AlertDocuments.date(now))))));
            if (operation.equals("VERIFY") || operation.equals("RELEASE")) {
                var snapshot = OwnerReviewDocuments.read(next);
                Instant deadline = policy.validUntil(snapshot.offer());
                if (snapshot.previewUntil().isBefore(deadline)) deadline = snapshot.previewUntil();
                filters.add(new Document("$expr", new Document("$lt", List.of("$$NOW", AlertDocuments.date(deadline)))));
            }
            var filter = and(filters);
            if (db.collection(REVIEWS).replaceOne(session, filter, next, new ReplaceOptions().collation(SIMPLE)).getMatchedCount() != 1)
                throw new Conflict(revision);
            db.collection(AUDIT).insertOne(session, AlertDocuments.base(action).append("reviewId", id).append("ownerId", ownerId)
                    .append("operation", operation).append("revision", revision + 1).append("fingerprint", fingerprint)
                    .append("materialHash", next.getString("materialHash")).append("maximumAttempts", maximum).append("at", AlertDocuments.date(now)));
            return new Result(Status.APPLIED, revision + 1);
        }); } catch (Conflict conflict) { return new Result(Status.CONFLICT, conflict.revision); }
    }
    /** Read current durable release inside send transaction; no permissive fallback for legacy/missing records. */
    static Document releaseFor(ClientSession session, MongoAlertDatabase db, String ownerId, Offer offer,
                               AutomaticSendEngine.PreparedPayload payload, Instant now) {
        if (ownerId == null) return null;
        var raw = db.collection(REVIEWS).find(session, eq("_id", CatalogDocuments.offerId(offer.key()))).collation(SIMPLE).first();
        if (raw == null || !ownerId.equals(raw.getString("ownerId"))) return null;
        var r = OwnerReviewDocuments.read(raw);
        if (r.state() != State.RELEASED || !now.isBefore(r.releaseUntil().orElseThrow())
                || now.isBefore(AlertDocuments.instant(raw, "releasedAt")) || r.usedAttempts() >= r.maximumAttempts()) return null;
        var latestContent = OwnerReviewDocuments.content(raw.get("content", Document.class), offer);
        if (!r.materialHash().equals(PublicationReviewPolicy.materialHash(offer, latestContent))
                || !r.materialHash().equals(payload.reviewMaterialHash()) || !r.templateVersion().equals(payload.templateVersion())) return null;
        return raw;
    }
    /** Must be in the SAME transaction as SENDING/attempt persistence; rollback also rolls back the budget. */
    static boolean reserve(ClientSession session, MongoAlertDatabase db, Document release) {
        var filter = and(eq("_id", release.getString("_id")), eq("schemaVersion", 1), eq("ownerId", release.getString("ownerId")),
                eq("revision", AlertDocuments.number(release, "revision")), eq("state", State.RELEASED.name()),
                new Document("$expr", new Document("$and", List.of(
                        new Document("$gte", List.of("$$NOW", "$releasedAt")), new Document("$lt", List.of("$$NOW", "$releaseUntil")),
                        new Document("$lt", List.of("$usedAttempts", "$maximumAttempts"))))));
        return db.collection(REVIEWS).updateOne(session, filter, new Document("$inc", new Document("usedAttempts", 1)),
                new UpdateOptions().collation(SIMPLE)).getMatchedCount() == 1;
    }
    private static final class Conflict extends RuntimeException {
        final long revision;
        Conflict(long revision) { this.revision = revision; }
    }
}
