package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import java.time.*;
import java.util.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/** Token-fenced review posts on the existing offer document. */
final class FreebieReviewStore {
    record Claim(FreebieOffer offer, String token, boolean verifyFirst, String channelId) {}
    private final MongoCollection<Document> offers;
    FreebieReviewStore(MongoCollection<Document> offers) { this.offers = offers; }

    Optional<Claim> claim(Instant now) {
        var priority = claim(now, in("store", FreebieSection.MAJOR_PC.stores().stream().map(FreebieStore::id).toList()));
        return priority.isPresent() ? priority : claim(now, new Document());
    }

    private Optional<Claim> claim(Instant now, Bson section) {
        String token = UUID.randomUUID().toString();
        var previous = offers.findOneAndUpdate(and(section, eq("state", "PENDING"), eq("reviewMessageId", null),
                        or(eq("reviewPostUntil", null), lte("reviewPostUntil", Date.from(now))),
                        or(eq("endsAt", null), gt("endsAt", Date.from(now))), ne("gone", true)),
                combine(set("reviewPostToken", token), set("reviewPostUntil", Date.from(now.plusSeconds(180))),
                        set("reviewPostAttempted", true)),
                new FindOneAndUpdateOptions().sort(Sorts.ascending("discoveredAt", "_id")).returnDocument(ReturnDocument.BEFORE));
        if (previous == null) return Optional.empty();
        return Optional.of(new Claim(FreebieOffer.read(previous), token,
                previous.getBoolean("reviewPostAttempted", false) || previous.getDate("reviewPostUntil") != null,
                previous.getString("reviewPostChannelId")));
    }

    private static Bson owned(Claim claim, Instant now) {
        return and(eq("_id", claim.offer().id()), eq("state", "PENDING"), eq("reviewMessageId", null),
                eq("reviewPostToken", claim.token()), gt("reviewPostUntil", Date.from(now)), ne("gone", true),
                or(eq("endsAt", null), gt("endsAt", Date.from(now))));
    }

    boolean owns(Claim claim, Instant now) { return offers.countDocuments(owned(claim, now)) == 1; }

    boolean destination(Claim claim, String channelId, Instant now) {
        return offers.updateOne(and(owned(claim, now), or(eq("reviewPostChannelId", null), eq("reviewPostChannelId", channelId))),
                set("reviewPostChannelId", channelId)).getMatchedCount() == 1;
    }

    boolean sent(Claim claim, String channelId, String messageId, Instant now) {
        return offers.updateOne(and(owned(claim, now), eq("reviewPostChannelId", channelId)),
                combine(set("reviewMessageId", messageId), set("reviewChannelId", channelId),
                        unset("reviewPostToken"), unset("reviewPostUntil"))).getModifiedCount() == 1;
    }
}
