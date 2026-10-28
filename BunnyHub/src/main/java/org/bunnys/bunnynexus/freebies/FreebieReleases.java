package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.*;
import org.bson.Document;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/**
 * Owner approval of an Epic release ({@code FreebieReleases}, {@code _id} = the release key). Approval is two steps,
 * both single-document conditional writes:
 *
 * <ol>
 *   <li>{@link #preview}: the exact members the owner is shown (offer IDs and their revisions) are saved with an
 *       actor-bound token that expires in five minutes, against the release's current {@code version}.</li>
 *   <li>{@link #commit}: only if the token, actor, expiry and version still match <em>and</em> the members recomputed
 *       now are exactly the previewed ones, an immutable snapshot is appended, {@code version} advances and the members
 *       join {@code approvedMembers}.</li>
 * </ol>
 *
 * The committed snapshot is the authority for sending: delivery planning and announcement pages keyed by a release
 * only send members in {@code approvedMembers}. Per-game {@code APPROVED} states are reconciled from snapshots
 * afterwards (also after a crash) and never authorize a release send on their own.
 */
final class FreebieReleases {
    static final String COLLECTION = "FreebieReleases";
    static final Duration PREVIEW = Duration.ofMinutes(5);
    static final int MAX_SNAPSHOTS = 40;
    private final MongoCollection<Document> releases;

    FreebieReleases(MongoDatabase db) { releases = db.getCollection(COLLECTION); }

    void installIndexes() {
        releases.createIndex(Indexes.ascending("approvedMembers"), new IndexOptions().name("release_member"));
        releases.createIndex(Indexes.ascending("pendingReconcile"), new IndexOptions().name("release_reconcile").sparse(true));
    }

    /** One reviewed member: an offer and the revision the owner saw. */
    record Member(String offerId, long revision) {
        Document toDocument() { return new Document("offerId", offerId).append("revision", revision); }
        static Member of(FreebieOffer offer) { return new Member(offer.id(), offer.revision()); }
        static Member read(Document d) { return new Member(d.getString("offerId"), ((Number) d.get("revision")).longValue()); }
    }
    record Snapshot(String key, long version, String actor, Instant at, List<Member> members, boolean quiet) {}
    enum Outcome { COMMITTED, STALE, EXPIRED, NOT_YOURS, RACED }
    record Commit(Outcome outcome, Optional<Snapshot> snapshot) {}

    static String token() { return UUID.randomUUID().toString().replace("-", "").substring(0, 24); }

    /** Saves what the owner is about to confirm. Empty when another write moved the release meanwhile. */
    Optional<String> preview(String key, List<FreebieOffer> members, String actor, Instant now) {
        releases.updateOne(eq("_id", key), combine(setOnInsert("version", 0L), setOnInsert("approvedMembers", List.of()),
                setOnInsert("snapshots", List.of()), setOnInsert("createdAt", Date.from(now))), new UpdateOptions().upsert(true));
        var doc = releases.find(eq("_id", key)).first();
        if (doc == null) return Optional.empty();
        String token = token();
        Object version = doc.get("version");
        var preview = new Document("token", token).append("actor", actor).append("until", Date.from(now.plus(PREVIEW)))
                .append("version", version).append("members", members.stream().map(o -> Member.of(o).toDocument()).toList());
        return releases.updateOne(and(eq("_id", key), eq("version", version)), set("preview", preview)).getModifiedCount() == 1
                ? Optional.of(token) : Optional.empty();
    }

    /**
     * Commits the previewed members if nothing changed. {@code current}: the members recomputed now with the same rule
     * as the preview; any added, removed, changed (revision), handled or ended game makes it {@link Outcome#STALE}.
     */
    Commit commit(String key, String token, String actor, List<FreebieOffer> current, Instant now) {
        return commit(key, token, actor, current, now, false);
    }

    /** {@code quiet}: approved without a ping; kept in the immutable snapshot so recovery applies the same choice. */
    Commit commit(String key, String token, String actor, List<FreebieOffer> current, Instant now, boolean quiet) {
        var doc = releases.find(eq("_id", key)).first();
        Document preview = doc == null ? null : doc.get("preview", Document.class);
        if (preview == null || !token.equals(preview.getString("token"))) return new Commit(Outcome.RACED, Optional.empty());
        if (!actor.equals(preview.getString("actor"))) return new Commit(Outcome.NOT_YOURS, Optional.empty());
        if (!preview.getDate("until").toInstant().isAfter(now)) return new Commit(Outcome.EXPIRED, Optional.empty());
        List<Member> reviewed = preview.getList("members", Document.class).stream().map(Member::read).toList();
        List<Member> now_ = current.stream().map(Member::of).toList();
        if (!new HashSet<>(reviewed).equals(new HashSet<>(now_)) || reviewed.size() != now_.size())
            return new Commit(Outcome.STALE, Optional.empty());
        long version = ((Number) doc.get("version")).longValue() + 1;
        var snapshot = new Document("version", version).append("actor", actor).append("at", Date.from(now))
                .append("members", reviewed.stream().map(Member::toDocument).toList()).append("reconciled", false)
                .append("quiet", quiet);
        var updated = releases.updateOne(and(eq("_id", key), eq("version", preview.get("version")), eq("preview.token", token),
                        eq("preview.actor", actor), gt("preview.until", Date.from(now)),
                        new Document("$expr", new Document("$lt", List.of(new Document("$size", "$snapshots"), MAX_SNAPSHOTS)))),
                combine(push("snapshots", snapshot), set("version", version), unset("preview"), set("pendingReconcile", true),
                        addEachToSet("approvedMembers", reviewed.stream().map(Member::offerId).toList())));
        if (updated.getModifiedCount() != 1) return new Commit(Outcome.RACED, Optional.empty());
        return new Commit(Outcome.COMMITTED, Optional.of(new Snapshot(key, version, actor, now, reviewed, quiet)));
    }

    /** Whether a committed snapshot of this release covers the offer: the only authority for release-keyed sends. */
    boolean approved(String key, String offerId) {
        return releases.countDocuments(and(eq("_id", key), eq("approvedMembers", offerId))) == 1;
    }

    /** Snapshots committed but not yet reconciled into per-game states (a crash in between), oldest first. */
    List<Snapshot> unreconciled(int limit) {
        List<Snapshot> result = new ArrayList<>();
        for (Document d : releases.find(eq("pendingReconcile", true)).limit(limit))
            for (Document s : d.getList("snapshots", Document.class, List.of()))
                if (!s.getBoolean("reconciled", false))
                    result.add(new Snapshot(d.getString("_id"), ((Number) s.get("version")).longValue(), s.getString("actor"),
                            s.getDate("at").toInstant(), s.getList("members", Document.class).stream().map(Member::read).toList(),
                            s.getBoolean("quiet", false)));
        return result;
    }

    void reconciled(Snapshot snapshot) {
        releases.updateOne(and(eq("_id", snapshot.key()), eq("snapshots.version", snapshot.version())),
                set("snapshots.$.reconciled", true));
        releases.updateOne(and(eq("_id", snapshot.key()), not(elemMatch("snapshots", eq("reconciled", false)))), unset("pendingReconcile"));
    }

    /** A member changed after it was reviewed: it leaves the approval and must be reviewed again. */
    void withdraw(String key, String offerId, Instant now) {
        releases.updateOne(eq("_id", key), combine(pull("approvedMembers", offerId),
                push("withdrawn", new Document("offerId", offerId).append("at", Date.from(now)))));
    }

    Optional<Document> get(String key) { return Optional.ofNullable(releases.find(eq("_id", key)).first()); }
}
