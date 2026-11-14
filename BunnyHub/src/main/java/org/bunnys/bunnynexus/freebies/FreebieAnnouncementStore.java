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
 * Announcement pages ({@code FreebieAnnouncementPosts}): one Discord message per page key, channel and page. New rows use
 * their channel's drop ({@link FreebieDrops}) as the key; older rows keep their release or {@code offer:} key.
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
    /** Games per message of a release/offer page. Five compact cards fit Discord's 6000-character embed total. */
    static final int PAGE_SIZE = 5, MAX_PAGES = 4, MAX_SUBMISSIONS = 5;
    /**
     * Drop pages ({@link FreebieDrops}): up to Discord's 10 embeds per message (the compact cards shrink to fit the
     * 6000-character total), and enough follow-up pages for a large catch-up. Only page 1 ever pings.
     */
    static final int DROP_PAGE_SIZE = 10, MAX_DROP_PAGES = 10;

    static int capacity(String key) { return FreebieDrops.isDrop(key) ? DROP_PAGE_SIZE : PAGE_SIZE; }
    static int maxPages(String key) { return FreebieDrops.isDrop(key) ? MAX_DROP_PAGES : MAX_PAGES; }

    /** Roles page 1 mentions: {@code roleIds} on drop pages, the single {@code roleId} on older pages. */
    static List<String> roles(Document page) {
        var many = page.getList("roleIds", String.class);
        if (many != null) return many;
        return page.getString("roleId") == null ? List.of() : List.of(page.getString("roleId"));
    }
    private final MongoCollection<Document> pages, offers, deliveries;

    FreebieAnnouncementStore(MongoDatabase db) {
        pages = db.getCollection(COLLECTION);
        offers = db.getCollection(FreebieRepository.OFFERS);
        deliveries = db.getCollection(FreebieRepository.DELIVERIES);
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
        return pages.find(and(eq("key", key), eq("channelId", channelId))).sort(Sorts.ascending("page")).limit(maxPages(key) + 1)
                .into(new ArrayList<>());
    }

    Optional<Document> get(String id) { return Optional.ofNullable(pages.find(eq("_id", id)).first()); }

    /**
     * Creates the next page with its frozen first members, leased to {@code token}. Empty when another worker created
     * it first (the caller re-reads and follows that page).
     */
    Optional<Document> create(String key, String guildId, String channelId, int page, Optional<String> roleId,
                              List<String> offerIds, List<String> deliveryIds, String token, Duration lease, Instant now) {
        return createWithRoles(key, guildId, channelId, page, roleId.map(List::of).orElse(List.of()), offerIds, deliveryIds, token, lease, now);
    }

    /**
     * {@code roleIds}: every saved role the page mentions (deduplicated; empty for no ping). Only the lead page keeps
     * them: page 1, or for a drop the first page after earlier pages that were all withdrawn or rejected without a post
     * ({@code lead}). Drop pages record their {@code capacity}, so a later member joins only with room.
     */
    Optional<Document> createWithRoles(String key, String guildId, String channelId, int page, List<String> roleIds,
                              List<String> offerIds, List<String> deliveryIds, String token, Duration lease, Instant now) {
        String id = pageId(key, channelId, page);
        boolean drop = FreebieDrops.isDrop(key);
        boolean lead = page == 1 || (drop && pages.countDocuments(and(eq("key", key), eq("channelId", channelId), lt("page", page),
                in("state", "SENT", "SUBMITTING"))) == 0);
        List<String> roles = lead ? List.copyOf(new LinkedHashSet<>(roleIds)) : List.of();
        var doc = new Document("_id", id).append("schemaVersion", 1).append("key", key).append("guildId", guildId)
                .append("channelId", channelId).append("page", page).append("roleId", roles.isEmpty() ? null : roles.getFirst())
                .append("members", offerIds).append("deliveries", deliveryIds).append("state", "SUBMITTING")
                .append("token", token).append("leaseUntil", Date.from(now.plus(lease))).append("submissions", 0)
                .append("verifyFirst", false).append("revision", 1L).append("applied", 0L).append("createdAt", Date.from(now));
        if (drop) doc.append("drop", true).append("capacity", capacity(key)).append("roleIds", roles).append("lead", lead);
        try { pages.insertOne(doc); return Optional.of(doc); }
        catch (MongoWriteException race) {
            if (race.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) throw race;
            return Optional.empty();
        }
    }

    /** How long a page released after a failed or blocked attempt waits before any of its rows may try again. */
    static final Duration RETRY = Duration.ofMinutes(2);

    /**
     * Takes over a page whose earlier submitter is gone (no live lease). The page must then be verified first. A page
     * released after a failure also waits for its {@code retryAt}: every member row can take the page over, so without
     * it a page of ten games would retry ten times as fast as one alert and use up its send budget within a minute.
     */
    Optional<Document> takeOver(Document page, String token, Duration lease, Instant now) {
        return Optional.ofNullable(pages.findOneAndUpdate(and(eq("_id", page.getString("_id")), eq("state", "SUBMITTING"),
                        or(exists("leaseUntil", false), lte("leaseUntil", Date.from(now))),
                        or(exists("retryAt", false), lte("retryAt", Date.from(now)))),
                combine(set("token", token), set("leaseUntil", Date.from(now.plus(lease))), unset("retryAt")),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)));
    }

    /**
     * An owner retry of a row whose page is still open (uncertain, or out of send budget): the page is taken at once,
     * with a fresh send budget, and always verified against history first. Conditional on the last submission being
     * unchanged, so one confirmed retry resets the budget once; a live lease is never taken.
     */
    Optional<Document> retake(Document page, String token, Duration lease, Instant now) {
        return Optional.ofNullable(pages.findOneAndUpdate(and(eq("_id", page.getString("_id")), eq("state", "SUBMITTING"),
                        eq("submittedAt", page.getDate("submittedAt")),
                        or(exists("leaseUntil", false), lte("leaseUntil", Date.from(now)))),
                combine(set("token", token), set("leaseUntil", Date.from(now.plus(lease))), set("submissions", 0),
                        set("verifyFirst", true), set("reopenedAt", Date.from(now)), unset("retryAt")),
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
                        unset("finishedAt"), unset("retryAt")),
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

    /**
     * Releases the lease after an uncertain, blocked or retryable attempt; the next submitter checks history first, and
     * no row takes the page over before {@link #RETRY} has passed (the wait of the blocked row itself).
     */
    void release(String id, String token, Instant now) { release(id, token, now.plus(RETRY), now); }

    /** As {@link #release(String, String, Instant)}, with the alert backoff's own next attempt time. */
    void release(String id, String token, Instant retryAt, Instant now) {
        pages.updateOne(owned(id, token, now), combine(unset("token"), unset("leaseUntil"), set("retryAt", Date.from(retryAt))));
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

    // ------------------------------------------------------------------ uncertain pages no row can recover

    /**
     * A page that may have been posted ({@code verifyFirst}) is normally recovered by one of its rows. When every row
     * is final (the games ended or were stopped while the outcome was unknown, so the rows were cancelled), nothing
     * would ever look for the message again: a posted card would keep its claim button, and the open page would hold
     * retention. Such a page is reconciled on its own: found in history it is recorded as sent and refreshed to its
     * current state; not found it is withdrawn. It is never sent from here.
     */
    record Orphan(Document page, String token) {}

    private static final int ORPHAN_SCAN = 10;

    Optional<Orphan> claimOrphan(Instant now, Duration lease) {
        Bson free = and(eq("state", "SUBMITTING"), eq("verifyFirst", true),
                or(exists("leaseUntil", false), lte("leaseUntil", Date.from(now))),
                or(exists("reconcileDueAt", false), lte("reconcileDueAt", Date.from(now))));
        for (Document d : pages.find(free).sort(Sorts.ascending("reconcileDueAt")).limit(ORPHAN_SCAN)) {
            String id = d.getString("_id");
            if (deliveries.countDocuments(and(in("_id", d.getList("deliveries", String.class, List.of())),
                    in("state", "PENDING", "SENDING", "BLOCKED"))) > 0) {
                // A member row is still open and recovers the page itself; look again later.
                pages.updateOne(and(eq("_id", id), free), set("reconcileDueAt", Date.from(now.plus(RETRY))));
                continue;
            }
            String token = UUID.randomUUID().toString();
            var taken = pages.findOneAndUpdate(and(eq("_id", id), free),
                    combine(set("token", token), set("leaseUntil", Date.from(now.plus(lease))), unset("retryAt")),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            if (taken != null) return Optional.of(new Orphan(taken, token));
        }
        return Optional.empty();
    }

    /**
     * History found the page's message: recorded as sent, and one revision ahead so the next refresh rebuilds the
     * message from current state (ended or withdrawn games lose their claim).
     */
    boolean reconciled(String id, String token, String messageId, boolean publish, Instant now) {
        List<Bson> changes = new ArrayList<>(List.of(set("state", "SENT"), set("messageId", messageId), set("sentAt", Date.from(now)),
                set("applied", 1L), inc("revision", 1L), set("refreshDueAt", Date.from(now)), set("verifyFirst", false),
                set("reconciledAt", Date.from(now)), unset("token"), unset("leaseUntil"), unset("retryAt"), unset("reconcileDueAt")));
        if (publish) changes.add(set("publishPending", true));
        return pages.updateOne(owned(id, token, now), combine(changes)).getModifiedCount() == 1;
    }

    /** History could not be read (or the server is unavailable): the page stays uncertain and is checked again. */
    void reconcileLater(String id, String token, Instant next, Instant now) {
        pages.updateOne(owned(id, token, now), combine(unset("token"), unset("leaseUntil"), set("reconcileDueAt", Date.from(next))));
    }

    /** A later member joins a posted page with room: one conditional write, then a silent refresh edit. */
    boolean join(String id, String offerId, String deliveryId, Instant now) {
        return pages.updateOne(and(eq("_id", id), eq("state", "SENT"), ne("members", offerId),
                        new Document("$expr", new Document("$lt", List.of(new Document("$size", "$members"),
                                new Document("$ifNull", List.of("$capacity", PAGE_SIZE)))))),
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
        // exists("cardRevision") lets the partial index offer_card_revision (FreebieRepository) narrow the scan to
        // corrected offers; only the three fields used here are returned.
        for (Document d : offers.find(and(exists("cardRevision"), new Document("$expr", new Document("$gt",
                List.of("$cardRevision", new Document("$ifNull", List.of("$cardRevisionSeen", 0L)))))))
                .projection(Projections.include("_id", "cardRevision", "cardRevisionSeen")).limit(limit)) {
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
        String id = r.page().getString("_id");
        boolean done = pages.updateOne(and(eq("_id", id), eq("refreshToken", r.token())),
                combine(max("applied", r.revision()), set("refreshOutcome", outcome), set("refreshedAt", Date.from(now)),
                        set("refreshAttempts", 0), unset("refreshToken"), unset("refreshLeaseUntil"))).getModifiedCount() == 1;
        // Up to date: no longer due, so the per-second refresh claim skips this page in the index instead of reading it.
        // A touch or join in between raised the revision, so this misses and the page stays due.
        if (done) pages.updateOne(and(eq("_id", id), eq("revision", r.revision()), eq("applied", r.revision()),
                exists("refreshToken", false)), unset("refreshDueAt"));
        return done;
    }

    /**
     * Pages refreshed before {@link #refreshed} cleared the due time, and pages whose edit was not needed: up to
     * {@code limit} posted pages that are up to date lose their past {@code refreshDueAt}, one conditional write each.
     * @return how many were cleared; 0 once none is left
     */
    int settleRefreshDue(Instant now, int limit) {
        Bson settled = and(eq("state", "SENT"), lte("refreshDueAt", Date.from(now)),
                new Document("$expr", new Document("$lte", List.of("$revision", "$applied"))),
                or(exists("refreshLeaseUntil", false), lte("refreshLeaseUntil", Date.from(now))));
        int cleared = 0;
        for (Document d : pages.find(settled).projection(Projections.include("_id", "revision")).limit(limit))
            cleared += (int) pages.updateOne(and(eq("_id", d.getString("_id")), eq("revision", d.get("revision")), settled),
                    unset("refreshDueAt")).getModifiedCount();
        return cleared;
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

    /**
     * A page that may still post, publish or be edited. An unapplied revision only counts on a posted page: a failed
     * or withdrawn page has no message to edit (its {@code applied} stays 0), and must not hold retention for ever.
     */
    boolean openWorkFor(String offerId) {
        return pages.countDocuments(and(eq("members", offerId), or(eq("state", "SUBMITTING"), eq("publishPending", true),
                and(eq("state", "SENT"), new Document("$expr", new Document("$gt", List.of("$revision", "$applied"))))))) > 0;
    }

    /** Finished pages whose members have all been purged or settled long ago. Bounded per run. */
    long purge(Instant cutoff, int limit) {
        Bson old = and(in("state", "SENT", "FAILED", "CANCELLED"), lt("createdAt", Date.from(cutoff)), exists("publishPending", false),
                or(in("state", "FAILED", "CANCELLED"), new Document("$expr", new Document("$lte", List.of("$revision", "$applied")))));
        long deleted = 0;
        for (Document d : pages.find(old).projection(Projections.include("_id", "members")).limit(limit)) {
            List<String> members = d.getList("members", String.class, List.of());
            if (offers.countDocuments(and(in("_id", members), in("state", "PENDING", "APPROVED"))) > 0) continue;
            deleted += pages.deleteOne(and(eq("_id", d.getString("_id")), old)).getDeletedCount();
        }
        return deleted;
    }
}
