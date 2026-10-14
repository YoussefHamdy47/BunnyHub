package org.bunnys.bunnynexus.freebies;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
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
 * Users who asked for personal DM alerts ({@code FreebieDmSubscribers}): {@code _id} is the user ID, plus
 * {@code sections} (section codes), {@code state}, {@code slot}, timestamps and the daily counter. Nothing else
 * about the user is stored.
 *
 * <p>The subscriber cap is enforced by slots: a new subscriber takes the lowest free {@code slot} below the cap,
 * and a unique index on {@code slot} lets at most one document hold each, so concurrent subscribes can never
 * exceed the cap and nothing can drift. Lowering the cap later leaves existing subscribers alone. A new
 * subscription is {@code PENDING} (holding its slot) until the consent DM is accepted, then {@code ACTIVE}.
 */
public final class FreebieDmSubscribers {
    public static final String COLLECTION = "FreebieDmSubscribers";
    /** A consent DM that never got an answer (crash, timeout) frees its slot after this. */
    static final Duration PENDING_FOR = Duration.ofMinutes(10);

    public enum Reserve { RESERVED, EDITED, FULL, DISABLED, BUSY }

    private final MongoCollection<Document> rows;

    FreebieDmSubscribers(MongoDatabase db) { rows = db.getCollection(COLLECTION); }

    void installIndexes() {
        rows.createIndex(Indexes.ascending("slot"), new IndexOptions().name("dm_slot").unique(true));
        rows.createIndex(Indexes.ascending("state", "sections"), new IndexOptions().name("dm_sections"));
        rows.createIndex(Indexes.ascending("state", "pendingUntil"), new IndexOptions().name("dm_pending"));
    }

    /**
     * An existing active subscriber has their sections replaced ({@code EDITED}); anyone else gets a slot held
     * for the consent DM ({@code RESERVED}), unless every slot below {@code cap} is taken ({@code FULL}).
     */
    public Reserve reserve(String userId, Set<FreebieSection> sections, int cap, Instant now) {
        if (cap <= 0) return Reserve.DISABLED;
        List<String> codes = codes(sections);
        if (rows.updateOne(and(eq("_id", userId), eq("state", "ACTIVE")),
                combine(set("sections", codes), set("updatedAt", Date.from(now)))).getModifiedCount() == 1
                || rows.countDocuments(and(eq("_id", userId), eq("state", "ACTIVE"))) == 1) return Reserve.EDITED;
        // An abandoned consent attempt (crash, timeout) no longer holds its slot.
        rows.deleteOne(and(eq("_id", userId), eq("state", "PENDING"), lte("pendingUntil", Date.from(now))));
        for (int attempt = 0; attempt < 20; attempt++) {
            if (rows.countDocuments(eq("_id", userId)) > 0) return Reserve.BUSY;
            Set<Integer> taken = rows.distinct("slot", lt("slot", cap), Integer.class).into(new HashSet<>());
            if (taken.size() >= cap) return Reserve.FULL;
            int slot = 0;
            while (taken.contains(slot)) slot++;
            try {
                rows.insertOne(new Document("_id", userId).append("schemaVersion", 1).append("state", "PENDING")
                        .append("slot", slot).append("sections", codes).append("createdAt", Date.from(now))
                        .append("updatedAt", Date.from(now)).append("pendingUntil", Date.from(now.plus(PENDING_FOR))));
                return Reserve.RESERVED;
            } catch (MongoWriteException conflict) {
                if (ErrorCategory.fromErrorCode(conflict.getCode()) != ErrorCategory.DUPLICATE_KEY) throw conflict;
                // Either this user (another click) or someone else took that slot first; look again.
            }
        }
        return Reserve.FULL;
    }

    /** The consent DM was accepted: the subscription becomes real. */
    public boolean activate(String userId, Instant now) {
        return rows.updateOne(and(eq("_id", userId), eq("state", "PENDING")),
                combine(set("state", "ACTIVE"), set("activatedAt", Date.from(now)), set("updatedAt", Date.from(now)),
                        unset("pendingUntil"))).getModifiedCount() == 1;
    }

    /** The consent DM failed: free the slot again. */
    public boolean release(String userId) {
        return rows.deleteOne(and(eq("_id", userId), eq("state", "PENDING"))).getDeletedCount() == 1;
    }

    /** Consent attempts that never finished (crash between reserve and answer) free their slots. */
    long expirePending(Instant now) {
        return rows.deleteMany(and(eq("state", "PENDING"), lte("pendingUntil", Date.from(now)))).getDeletedCount();
    }

    /** Removes exactly this user's subscription. */
    public boolean unsubscribe(String userId) {
        return rows.deleteOne(eq("_id", userId)).getDeletedCount() == 1;
    }

    public Optional<Set<FreebieSection>> sections(String userId) {
        var d = rows.find(and(eq("_id", userId), eq("state", "ACTIVE"))).first();
        return d == null ? Optional.empty() : Optional.of(sections(d));
    }

    /** Active subscribers following {@code section}, as user IDs. */
    List<String> following(FreebieSection section) {
        return rows.find(and(eq("state", "ACTIVE"), eq("sections", String.valueOf(section.code()))))
                .projection(Projections.include("_id")).sort(Sorts.ascending("_id"))
                .map(d -> d.getString("_id")).into(new ArrayList<>());
    }

    long active() { return rows.countDocuments(eq("state", "ACTIVE")); }

    /**
     * Counts one game DM against today's limit; false when the user already had {@link FreebieDm#DAILY_CAP} today
     * or is no longer subscribed. Two conditional updates on the subscriber document, never a read-then-write.
     */
    boolean countToday(String userId, Instant now) {
        String today = FreebieDm.day(now);
        if (rows.updateOne(and(eq("_id", userId), eq("state", "ACTIVE"), eq("dayKey", today), lt("dayCount", FreebieDm.DAILY_CAP)),
                combine(inc("dayCount", 1), inc("sentTotal", 1))).getModifiedCount() == 1) return true;
        return rows.updateOne(and(eq("_id", userId), eq("state", "ACTIVE"), ne("dayKey", today)),
                combine(set("dayKey", today), set("dayCount", 1), inc("sentTotal", 1))).getModifiedCount() == 1;
    }

    static Set<FreebieSection> sections(Document d) {
        Set<FreebieSection> sections = EnumSet.noneOf(FreebieSection.class);
        for (String code : d.getList("sections", String.class, List.of())) FreebieSection.byCode(code).ifPresent(sections::add);
        return sections;
    }

    private static List<String> codes(Set<FreebieSection> sections) {
        return Arrays.stream(FreebieSection.values()).filter(sections::contains).map(s -> String.valueOf(s.code())).toList();
    }
}
