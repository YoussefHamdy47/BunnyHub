package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import java.time.*;
import java.util.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/** Delivery recovery and owner retry metadata, in the existing delivery document. */
final class FreebieDeliveryStore {
    enum Block {
        HISTORY_PERMISSION("Restore Read Message History before recovery."),
        HISTORY_FAILED("History could not be read; recovery will check again."),
        DESTINATION("Restore destination access before recovery."),
        UNCERTAIN("Discord acceptance is uncertain; history verification is required."),
        EXHAUSTED("Automatic send budget exhausted; owner confirmation required.");
        final String text;
        Block(String text) { this.text = text; }
    }
    static final int PAGE_SIZE = 5;
    private final MongoCollection<Document> rows;
    FreebieDeliveryStore(MongoCollection<Document> rows) { this.rows = rows; }
    void installIndexes() {
        rows.createIndex(Indexes.ascending("offerId", "_id"), new IndexOptions().name("delivery_history"));
    }
    static Bson owned(FreebieRepository.Claimed c, Instant now) {
        return and(eq("_id", c.id()), eq("state", "SENDING"), eq("leaseToken", c.token()), gt("leaseUntil", Date.from(now)));
    }
    boolean owns(FreebieRepository.Claimed c, Instant now) { return rows.countDocuments(owned(c, now)) == 1; }
    boolean block(FreebieRepository.Claimed c, Block reason, Instant now) {
        return rows.updateOne(owned(c, now), combine(set("state", "BLOCKED"), set("verifyFirst", true),
                set("blockedReason", reason.name()), set("blockedAt", Date.from(now)),
                set("nextAttemptAt", Date.from(now.plusSeconds(120))), inc("deliveryRevision", 1L),
                unset("leaseToken"), unset("leaseUntil"))).getModifiedCount() == 1;
    }
    /** Persist uncertainty before submitting REST, even if acceptance cannot subsequently be recorded. */
    boolean submitting(FreebieRepository.Claimed c, Instant now) {
        return rows.updateOne(and(owned(c, now), or(exists("sendAttempts", false),
                        new Document("$expr", new Document("$lt", List.of("$sendAttempts",
                                new Document("$add", List.of(new Document("$ifNull", List.of("$retryBaseline", 0)), 5))))))),
                combine(set("verifyFirst", true), set("submittedAt", Date.from(now)), inc("sendAttempts", 1)))
                .getModifiedCount() == 1;
    }
    Optional<Document> get(String id) { return Optional.ofNullable(rows.find(eq("_id", id)).first()); }
    List<Document> page(String offer, String after) {
        return rows.find(and(eq("offerId", offer), gt("_id", after))).sort(Sorts.ascending("_id"))
                .limit(PAGE_SIZE + 1).into(new ArrayList<>());
    }
    List<Document> blocked() {
        return rows.find(eq("state", "BLOCKED")).sort(Sorts.ascending("nextAttemptAt", "_id"))
                .limit(3).into(new ArrayList<>());
    }
    static boolean uncertain(Document d) {
        return d.getBoolean("verifyFirst", false) || "TRANSIENT".equals(d.getString("failure"));
    }
    static String reason(Document d) {
        try { return Block.valueOf(d.getString("blockedReason")).text; }
        catch (IllegalArgumentException | NullPointerException ignored) { return "No blocked reason recorded."; }
    }
    /** Preview is bound to one actor and one unchanged delivery revision, expires in five minutes. */
    Optional<String> preview(Document d, String actor, Instant now) {
        String token = UUID.randomUUID().toString().replace("-", "");
        var changed = rows.updateOne(and(eq("_id", d.getString("_id")), in("state", "FAILED", "BLOCKED"),
                        eq("deliveryRevision", d.get("deliveryRevision"))),
                combine(set("retryPreviewToken", token), set("retryPreviewActor", actor),
                        set("retryPreviewUntil", Date.from(now.plusSeconds(300))),
                        set("retryPreviewRevision", d.get("deliveryRevision"))));
        return changed.getModifiedCount() == 1 ? Optional.of(token) : Optional.empty();
    }
    boolean retry(Document d, String actor, String token, Instant now) {
        // Keep all outcomes and actor/time records. Refuse more retries before the BSON document could grow without bound.
        return rows.updateOne(and(eq("_id", d.getString("_id")), in("state", "FAILED", "BLOCKED"),
                        eq("retryPreviewToken", token), eq("retryPreviewActor", actor),
                        gt("retryPreviewUntil", Date.from(now)), eq("deliveryRevision", d.get("retryPreviewRevision")),
                        or(exists("manualRetries", false), lt("manualRetries", 100))),
                combine(set("state", "PENDING"), set("nextAttemptAt", Date.from(now)),
                        set("retryBaseline", d.getInteger("sendAttempts", 0)), set("verifyFirst", uncertain(d)),
                        inc("manualRetries", 1), inc("deliveryRevision", 1L), unset("retryPreviewToken"),
                        push("retryAudit", new Document("actor", actor).append("at", Date.from(now))
                                .append("previousState", d.getString("state")).append("attempts", d.getInteger("attempts", 0))
                                .append("failure", d.getString("failure")).append("blockedReason", d.getString("blockedReason"))
                                .append("finishedAt", d.getDate("finishedAt"))
                                .append("uncertain", uncertain(d)))))
                .getModifiedCount() == 1;
    }
}





