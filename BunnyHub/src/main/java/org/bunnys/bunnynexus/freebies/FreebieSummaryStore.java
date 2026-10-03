package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/** Compatible summary delivery metadata on the existing offer document. */
final class FreebieSummaryStore {
    record Claim(FreebieOffer offer, String channelId, String token, boolean verifyFirst) {}
    private final MongoCollection<Document> offers;

    FreebieSummaryStore(MongoCollection<Document> offers) { this.offers = offers; }

    void installIndexes() {
        offers.createIndex(Indexes.ascending("state", "summaryPosted", "summaryNextAttemptAt", "summaryLeaseUntil"),
                new IndexOptions().name("summary_due"));
    }

    private static Bson due(Instant now) {
        return and(in("state", "COMPLETED", "STOPPED"), ne("summaryPosted", true),
                or(eq("summaryNextAttemptAt", null), lte("summaryNextAttemptAt", Date.from(now))),
                or(eq("summaryLeaseUntil", null), lte("summaryLeaseUntil", Date.from(now))));
    }

    List<FreebieOffer> awaiting(Instant now, int limit) {
        return offers.find(due(now)).sort(Sorts.ascending("summaryNextAttemptAt", "discoveredAt", "_id"))
                .limit(limit).map(FreebieOffer::read).into(new ArrayList<>());
    }

    Optional<Claim> claim(String offerId, String fallbackChannel, Instant now, Duration lease) {
        String token = UUID.randomUUID().toString();
        // Preserve the first destination even if configuration changes after an uncertain send.
        var fields = new Document("summaryToken", token).append("summaryLeaseUntil", Date.from(now.plus(lease)))
                .append("summaryAttempted", true)
                .append("summaryChannelId", new Document("$ifNull", List.of("$summaryChannelId",
                        new Document("$ifNull", List.of("$reviewChannelId", fallbackChannel)))));
        var previous = offers.findOneAndUpdate(and(eq("_id", offerId), due(now)),
                List.of(new Document("$set", fields)), new FindOneAndUpdateOptions().returnDocument(ReturnDocument.BEFORE));
        if (previous == null) return Optional.empty();
        var offer = FreebieOffer.read(previous);
        String channel = previous.getString("summaryChannelId");
        return Optional.of(new Claim(offer, channel == null ? offer.reviewChannelId().orElse(fallbackChannel) : channel,
                token, previous.getBoolean("summaryAttempted", false)));
    }

    private Bson owned(Claim claim, Instant now) {
        return and(eq("_id", claim.offer().id()), ne("summaryPosted", true), eq("summaryToken", claim.token()),
                gt("summaryLeaseUntil", Date.from(now)));
    }

    boolean owns(Claim claim, Instant now) { return offers.countDocuments(owned(claim, now)) == 1; }

    boolean sent(Claim claim, String messageId, Instant now) {
        return offers.updateOne(owned(claim, now), combine(set("summaryPosted", true), set("summaryMessageId", messageId),
                set("summaryPostedAt", Date.from(now)), unset("summaryToken"), unset("summaryLeaseUntil"),
                unset("summaryNextAttemptAt"))).getModifiedCount() == 1;
    }

    boolean retry(Claim claim, Instant now) {
        return offers.updateOne(owned(claim, now), combine(set("summaryNextAttemptAt", Date.from(now.plusSeconds(120))),
                unset("summaryToken"), unset("summaryLeaseUntil"))).getModifiedCount() == 1;
    }
}
