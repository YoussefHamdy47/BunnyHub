package org.bunnys.bunnynexus.freebies;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
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
 * Announcement pages ({@code FreebieAnnouncementPosts}): one Discord message per release group, channel and page.
 * {@code _id} is {@code <key>|<channelId>|<page>}, so two workers can never create the same page. The page, not the
 * per-offer delivery row, is the send unit: rows only follow the page's outcome. Membership is frozen while a
 * submission may be in flight; a later member joins a posted page silently (an edit, never a new ping).
 *
 * <p>{@code revision}/{@code applied}: every change that should reach the posted message (a member joined, ended or
 * was corrected) increments {@code revision}; one leased refresh at a time rebuilds the whole message from current
 * state and records the revision it read. Concurrent changes therefore never overwrite each other.
 */
final class FreebieAnnouncementStore {
    static final String COLLECTION = "FreebieAnnouncementPosts";
    /** Games per message. Five compact cards stay inside Discord's 6000-character embed total and 5 button rows. */
    static final int PAGE_SIZE = 5, MAX_PAGES = 4, MAX_SUBMISSIONS = 5;
    private final MongoCollection<Document> pages, offers;

    FreebieAnnouncementStore(MongoDatabase db) {
        pages = db.getCollection(COLLECTION);
        offers = db.getCollection(FreebieRepository.OFFERS);
    }

    void installIndexes() {
        pages.createIndex(Indexes.ascending("key", "channelId", "page"), new IndexOptions().name("announcement_page"));
        pages.createIndex(Indexes.ascending("members"), new IndexOptions().name("announcement_member"));
        pages.createIndex(Indexes.ascending("state", "refreshDueAt"), new IndexOptions().name("announcement_refresh"));
        pages.createIndex(Indexes.ascending("publishPending"), new IndexOptions().name("announcement_publish").sparse(true));
    }

    static String pageId(String key, String channelId, int page) { return key + "|" + channelId + "|" + page; }
    static String nonce(String pageId) { return FreebieMessages.nonce("page|" + pageId); }

    List<Document> pages(String key, String channelId) {
        return pages.find(and(eq("key", key), eq("channelId", channelId))).sort(Sorts.ascending("page")).limit(MAX_PAGES + 1)
                .into(new ArrayList<>());
    }

    Optional<Document> get(String id) { return Optional.ofNullable(pages.find(eq("_id", id)).first()); }

    /**
     * Creates the next page with its frozen first members, leased to {@code token}. Empty when another worker created
     * it first (the caller re-reads and follows that page).
     */
    Optional<Document> create(String key, String guildId, String channelId, int page, Optional<String> roleId,
                              List<String> offerIds, List<String> deliveryIds, String token, Duration lease, Instant now) {
        String id = pageId(key, channelId, page);
        var doc = new Document("_id", id).append("schemaVersion", 1).append("key", key).append("guildId", guildId)
                .append("channelId", channelId).append("page", page).append("roleId", page == 1 ? roleId.orElse(null) : null)
                .append("members", offerIds).append("deliveries", deliveryIds).append("state", "SUBMITTING")
                .append("token", token).append("leaseUntil", Date.from(now.plus(lease))).append("submissions", 0)
                .append("verifyFirst", false).append("revision", 1L).append("applied", 0L).append("createdAt", Date.from(now));
        try { pages.insertOne(doc); return Optional.of(doc); }
        catch (MongoWriteException race) {
            if (race.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) throw race;
            return Optional.empty();
        }
    }

    /** Takes over a page whose earlier submitter is gone (no live lease). The page must then be verified first. */
    Optional<Document> takeOver(Document page, String token, Duration lease, Instant now) {
        return Optional.ofNullable(pages.findOneAndUpdate(and(eq("_id", page.getString("_id")), eq("state", "SUBMITTING"),
                        or(exists("leaseUntil", false), lte("leaseUntil", Date.from(now)))),
                combine(set("token", token), set("leaseUntil", Date.from(now.plus(lease)))),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)));
    }

    /**
     * An owner retry of a failed page: back to SUBMITTING with a fresh send budget, always verified against history
     * first. Conditional on the failure being unchanged, so concurrent retries reopen it once.
     */
    Optional<Document> reopen(Document page, String token, Duration lease, Instant now) {
        return Optional.ofNullable(pages.findOneAndUpdate(and(eq("_id", page.getString("_id")), eq("state", "FAILED"),
                        eq("finishedAt", page.getDate("finishedAt"))),
                combine(set("state", "SUBMITTING"), set("token", token), set("leaseUntil", Date.from(now.plus(lease))),
                        set("submissions", 0), set("verifyFirst", true), set("reopenedAt", Date.from(now)), unset("failure"),
                        unset("finishedAt")),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)));
    }

    static Bson owned(String id, String token, Instant now) {
        return and(eq("_id", id), eq("state", "SUBMITTING"), eq("token", token), gt("leaseUntil", Date.from(now)));
    }

    /** Persists uncertainty before REST submission. False when the lease is lost or the send budget is used up. */
    boolean submitting(String id, String token, Instant now) {
        return pages.updateOne(and(owned(id, token, now), lt("submissions", MAX_SUBMISSIONS)),
                combine(set("verifyFirst", true), set("submittedAt", Date.from(now)), inc("submissions", 1))).getModifiedCount() == 1;
    }

    /**
     * Discord accepted the page (or history found it). {@code publish}: the channel is on the automatic-publishing
     * allowlist, so a publication job must be planned; recorded in this same write so a crash cannot lose it.
     */
    boolean sent(String id, String token, String messageId, boolean publish, Instant now) {
        List<Bson> changes = new ArrayList<>(List.of(set("state", "SENT"), set("messageId", messageId), set("sentAt", Date.from(now)),
                set("applied", 1L), set("verifyFirst", false), unset("token"), unset("leaseUntil")));
        if (publish) changes.add(set("publishPending", true));
        return pages.updateOne(owned(id, token, now), combine(changes)).getModifiedCount() == 1;
    }

    /** Releases the lease after an uncertain or retryable failure; the next submitter checks history first. */
    void release(String id, String token, Instant now) {
        pages.updateOne(owned(id, token, now), combine(unset("token"), unset("leaseUntil")));
    }

    /** A definite, permanent rejection. Member rows follow with the same failure. */
    boolean failed(String id, String token, String failure, Instant now) {
        return pages.updateOne(owned(id, token, now), combine(set("state", "FAILED"), set("failure", failure),
                set("finishedAt", Date.from(now)), unset("token"), unset("leaseUntil"))).getModifiedCount() == 1;
    }

    /**
     * Nothing on the page is eligible any more, and it was either never submitted or history verification found no
     * earlier post: it is withdrawn without a message.
     */
    boolean cancel(String id, String token, Instant now) {
        return pages.updateOne(owned(id, token, now), combine(set("state", "CANCELLED"),
                set("finishedAt", Date.from(now)), unset("token"), unset("leaseUntil"))).getModifiedCount() == 1;
    }

    /** A later member joins a posted page with room: one conditional write, then a silent refresh edit. */
    boolean join(String id, String offerId, String deliveryId, Instant now) {
        return pages.updateOne(and(eq("_id", id), eq("state", "SENT"), ne("members", offerId),
                        new Document("$expr", new Document("$lt", List.of(new Document("$size", "$members"), PAGE_SIZE)))),
                combine(push("members", offerId), push("deliveries", deliveryId), inc("revision", 1L),
                        set("refreshDueAt", Date.from(now)))).getModifiedCount() == 1;
    }

    /** Something shown on these members' posted pages changed (ended, withdrawn, corrected). */
    long touch(String offerId, Instant now) {
        // SUBMITTING too: a change during submission leaves revision ahead of the applied 1, so the page is refreshed.
        return pages.updateMany(and(eq("members", offerId), in("state", "SENT", "SUBMITTING")),
                combine(inc("revision", 1L), set("refreshDueAt", Date.from(now)))).getModifiedCount();
    }

    /** Offers whose saved card details changed since pages last heard about it ({@code cardRevision}). */
    int propagateCorrections(Instant now, int limit) {
        int touched = 0;
        for (Document d : offers.find(and(exists("cardRevision"), new Document("$expr", new Document("$gt",
                List.of("$cardRevision", new Document("$ifNull", List.of("$cardRevisionSeen", 0L))))))).limit(limit)) {
            Object revision = d.get("cardRevision");
            touch(d.getString("_id"), now);
            offers.updateOne(and(eq("_id", d.getString("_id")), eq("cardRevision", revision)), set("cardRevisionSeen", revision));
            touched++;
        }
        return touched;
    }

    // ------------------------------------------------------------------ serialized refresh edits

    record Refresh(Document page, long revision, String token) {}

    Optional<Refresh> claimRefresh(Instant now, Duration lease) {
        String token = UUID.randomUUID().toString();
        var d = pages.findOneAndUpdate(and(eq("state", "SENT"), lte("refreshDueAt", Date.from(now)),
                        new Document("$expr", new Document("$gt", List.of("$revision", "$applied"))),
                        or(exists("refreshLeaseUntil", false), lte("refreshLeaseUntil", Date.from(now)))),
                combine(set("refreshToken", token), set("refreshLeaseUntil", Date.from(now.plus(lease))), inc("refreshAttempts", 1)),
                new FindOneAndUpdateOptions().sort(Sorts.ascending("refreshDueAt")).returnDocument(ReturnDocument.AFTER));
        return d == null ? Optional.empty() : Optional.of(new Refresh(d, ((Number) d.get("revision")).longValue(), token));
    }

    boolean ownsRefresh(Refresh r, Instant now) {
        return pages.countDocuments(and(eq("_id", r.page().getString("_id")), eq("refreshToken", r.token()),
                gt("refreshLeaseUntil", Date.from(now)))) == 1;
    }

    /** The edit for {@code revision} landed (or was not possible); later revisions stay due. */
    boolean refreshed(Refresh r, String outcome, Instant now) {
        return pages.updateOne(and(eq("_id", r.page().getString("_id")), eq("refreshToken", r.token())),
                combine(max("applied", r.revision()), set("refreshOutcome", outcome), set("refreshedAt", Date.from(now)),
                        set("refreshAttempts", 0), unset("refreshToken"), unset("refreshLeaseUntil"))).getModifiedCount() == 1;
    }

    void refreshRetry(Refresh r, Instant next, String detail) {
        pages.updateOne(and(eq("_id", r.page().getString("_id")), eq("refreshToken", r.token())),
                combine(set("refreshDueAt", Date.from(next)), set("refreshOutcome", detail), unset("refreshToken"), unset("refreshLeaseUntil")));
    }

    boolean settled(Document page) {
        return ((Number) page.get("revision")).longValue() <= ((Number) page.get("applied")).longValue();
    }

    // ------------------------------------------------------------------ publication planning

    List<Document> publishPending(int limit) {
        return pages.find(eq("publishPending", true)).limit(limit).into(new ArrayList<>());
    }

    void publishPlanned(String id) { pages.updateOne(eq("_id", id), unset("publishPending")); }

    /** Publishing was disabled or the channel left the allowlist: pages sent meanwhile are never published later. */
    long dropPublishPending(Optional<Set<String>> keepChannels) {
        Bson filter = keepChannels.map(keep -> and(eq("publishPending", true), nin("channelId", keep))).orElse(eq("publishPending", true));
        return pages.updateMany(filter, unset("publishPending")).getModifiedCount();
    }

    boolean openWorkFor(String offerId) {
        return pages.countDocuments(and(eq("members", offerId), or(eq("state", "SUBMITTING"), eq("publishPending", true),
                new Document("$expr", new Document("$gt", List.of("$revision", "$applied")))))) > 0;
    }

    /** Finished pages whose members have all been purged or settled long ago. Bounded per run. */
    long purge(Instant cutoff, int limit) {
        Bson old = and(in("state", "SENT", "FAILED", "CANCELLED"), lt("createdAt", Date.from(cutoff)), exists("publishPending", false),
                new Document("$expr", new Document("$lte", List.of("$revision", "$applied"))));
        long deleted = 0;
        for (Document d : pages.find(old).projection(Projections.include("_id", "members")).limit(limit)) {
            List<String> members = d.getList("members", String.class, List.of());
            if (offers.countDocuments(and(in("_id", members), in("state", "PENDING", "APPROVED"))) > 0) continue;
            deleted += pages.deleteOne(and(eq("_id", d.getString("_id")), old)).getDeletedCount();
        }
        return deleted;
    }
}
