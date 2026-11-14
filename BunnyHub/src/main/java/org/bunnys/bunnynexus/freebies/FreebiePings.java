package org.bunnys.bunnynexus.freebies;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/**
 * Per-channel ping budget (FREEBIE_REDESIGN.md step 4): a post that would mention a role first takes the channel's
 * ping; within the cooldown of the channel's last ping it posts without the mention.
 *
 * <p>One document per channel ({@code _id} = channel ID), not one per subscription: a channel has one subscription
 * document per launcher, so a ledger there could not be one single-document conditional write per channel. The take
 * is one conditional upsert. It is keyed by the post ({@code lastPingKey}: the drop or page key, a delivery ID for a
 * per-game post, a reminder job ID), so a retry of the same post, or a later lead page of the same drop, gets the
 * same answer and a ping is never used twice for one post nor split across two.
 */
final class FreebiePings {
    static final String COLLECTION = "FreebieChannelPings";
    static final Duration DEFAULT_COOLDOWN = Duration.ofMinutes(60), MAX_COOLDOWN = Duration.ofDays(1);

    private final MongoCollection<Document> pings;
    private volatile Duration cooldown = DEFAULT_COOLDOWN;

    FreebiePings(MongoDatabase db) { pings = db.getCollection(COLLECTION); }

    /** Zero turns the budget off: every post that may ping does. */
    FreebiePings cooldown(Duration value) { cooldown = Objects.requireNonNull(value); return this; }
    Duration cooldown() { return cooldown; }

    /** The outcome of asking for a channel's ping: allowed, or held with the time of the ping that holds it. */
    record Take(boolean allowed, Optional<Instant> lastPingAt) {
        static final Take ALLOWED = new Take(true, Optional.empty());
    }

    /** @return whether the post {@code key} may mention its roles in {@code channelId}. */
    Take take(String guildId, String channelId, String key, Instant now) {
        Duration window = cooldown;
        if (window.isZero() || window.isNegative()) return Take.ALLOWED;
        Bson free = and(eq("_id", channelId), ne("lastPingKey", key),
                or(exists("lastPingAt", false), lte("lastPingAt", Date.from(now.minus(window)))));
        try {
            var result = pings.updateOne(free, combine(set("guildId", guildId), set("lastPingAt", Date.from(now)), set("lastPingKey", key)),
                    new UpdateOptions().upsert(true));
            if (result.getModifiedCount() == 1 || result.getUpsertedId() != null) return Take.ALLOWED;
        } catch (MongoWriteException duplicate) {
            // The channel's document exists and did not match: pinged within the cooldown, or already by this post.
            if (duplicate.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) throw duplicate;
        }
        Document current = pings.find(eq("_id", channelId)).first();
        if (current == null) return Take.ALLOWED; // Purged in between: nothing holds the channel's ping.
        if (key.equals(current.getString("lastPingKey"))) return Take.ALLOWED;
        return new Take(false, Optional.ofNullable(current.getDate("lastPingAt")).map(Date::toInstant));
    }

    /** Ledger documents whose last ping is older than {@code cutoff}; they hold nothing. Bounded per run. */
    long purge(Instant cutoff, int limit) {
        Bson old = lt("lastPingAt", Date.from(cutoff));
        List<Object> ids = pings.find(old).projection(Projections.include("_id")).limit(limit).map(d -> d.get("_id")).into(new ArrayList<>());
        return ids.isEmpty() ? 0 : pings.deleteMany(and(in("_id", ids), old)).getDeletedCount();
    }

    /** "pinged 12 min ago", for the log line of a post that went out without its mention. */
    static String since(Take take, Instant now) {
        return take.lastPingAt().map(at -> "pinged " + Math.max(0, Duration.between(at, now).toMinutes()) + " min ago").orElse("pinged recently");
    }
}
