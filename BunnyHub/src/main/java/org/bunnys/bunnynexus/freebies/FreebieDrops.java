package org.bunnys.bunnynexus.freebies;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import org.bson.Document;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/**
 * Drops: every first post due for one channel inside a short window becomes one message with one ping.
 *
 * <p>{@code FreebieDropWindows} holds one document per channel ({@code _id} = channel ID) with the channel's current
 * window: {@code key}, {@code start}, {@code until}. The first delivery planned for a channel opens a window
 * ({@code until} = now + window); every row planned for that channel before {@code until} joins it. Each row gets
 * {@code dropKey} = the window's key and {@code nextAttemptAt} = {@code until}, and the drop key is the page key of
 * {@link FreebieAnnouncementStore}, so the existing page lease, nonce, history check and silent joins apply to the drop.
 *
 * <p>Opening is one conditional upsert on the channel's document: either the open window is read, or an expired one is
 * replaced; two planners racing to open one both end up with the same key (the loser's upsert hits the unique
 * {@code _id} and re-reads). A row planned just before {@code until} but inserted after the page was posted joins it
 * silently (or a later page, never pinged).
 *
 * <p>Catch-up batches ("Post current games now") are drops of their own, keyed per request and never pinged.
 */
final class FreebieDrops {
    static final String COLLECTION = "FreebieDropWindows", PREFIX = "drop|", CATCH_UP = "|catch-up";
    /**
     * Catch-up rows are inserted one by one and held until the request has inserted them all
     * ({@link FreebieRepository#releaseDrop}), so the whole batch shares one message however slow the request is. A
     * request that dies before releasing them is released by this hold running out.
     */
    static final Duration CATCH_UP_HOLD = Duration.ofMinutes(5);
    private static final int ATTEMPTS = 5;
    private final MongoCollection<Document> windows;

    FreebieDrops(MongoDatabase db) { windows = db.getCollection(COLLECTION); }

    record Window(String key, Instant until) {}

    static boolean isDrop(String key) { return key != null && key.startsWith(PREFIX); }
    static boolean isCatchUp(String key) { return isDrop(key) && key.endsWith(CATCH_UP); }
    static String key(String channelId, Instant start) { return PREFIX + channelId + "|" + start.toEpochMilli(); }
    static String catchUpKey(String channelId, Instant now) { return key(channelId, now) + CATCH_UP; }

    /** The channel's open window, or a new one starting now. Safe to call concurrently from several planners. */
    Window open(String channelId, Duration window, Instant now) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            var current = windows.find(and(eq("_id", channelId), gt("until", Date.from(now)))).first();
            if (current != null) return read(current);
            Instant until = now.plus(window);
            try {
                var opened = windows.findOneAndUpdate(and(eq("_id", channelId), or(exists("until", false), lte("until", Date.from(now)))),
                        combine(set("key", key(channelId, now)), set("start", Date.from(now)), set("until", Date.from(until))),
                        new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
                if (opened != null) return read(opened);
            } catch (MongoWriteException | MongoCommandException race) {
                if (!duplicate(race)) throw race;
                // Another planner opened the window between our read and our upsert: read it on the next pass.
            }
        }
        throw new IllegalStateException("Could not open a drop window for channel " + channelId);
    }

    private static boolean duplicate(RuntimeException error) {
        if (error instanceof MongoWriteException write) return write.getError().getCategory() == ErrorCategory.DUPLICATE_KEY;
        return error instanceof MongoCommandException command && ErrorCategory.fromErrorCode(command.getErrorCode()) == ErrorCategory.DUPLICATE_KEY;
    }

    /** One small document per channel ever used, overwritten by each new window; nothing to purge. */
    private static Window read(Document d) { return new Window(d.getString("key"), d.getDate("until").toInstant()); }
}
