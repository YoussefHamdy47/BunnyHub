package org.bunnys.bunnynexus.deals;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Updates.*;

/**
 * The lowest sale price seen for each game on the deals list ({@code DealPrices}: one small document per game ever
 * listed, {@code _id} = deal ID). A deal is "the lowest price we've seen" when it is at (or below) its lowest tracked
 * price and the game has been tracked for at least {@link #HISTORY}, so a game first seen today never gets the badge.
 * Read and written once per refresh (at most 60 games), on the deals thread.
 */
final class DealPrices {
    static final String COLLECTION = "DealPrices";
    /** How long a game must have been tracked before its price can be called the lowest. */
    static final Duration HISTORY = Duration.ofDays(14);

    /** What is known about one game's price, or nothing before the first sighting. */
    record Seen(long lowCents, Instant firstSeen) {}

    interface Store {
        Map<String, Seen> seen(Collection<String> ids);
        void record(List<Deal> deals, Map<String, Seen> before, Instant now);
    }

    /** Deals at their lowest tracked price, given what was known before this refresh. Pure. */
    static Set<String> lowest(List<Deal> deals, Map<String, Seen> before, Instant now) {
        Set<String> low = new HashSet<>();
        for (Deal deal : deals) {
            var seen = before.get(deal.id());
            if (seen != null && !seen.firstSeen().plus(HISTORY).isAfter(now) && deal.saleCents() <= seen.lowCents()) low.add(deal.id());
        }
        return low;
    }

    /** Records this refresh's prices and returns the deals at their lowest tracked price. */
    static Set<String> update(Store store, List<Deal> deals, Instant now) {
        var before = store.seen(deals.stream().map(Deal::id).toList());
        var low = lowest(deals, before, now);
        store.record(deals, before, now);
        return low;
    }

    /** The same answer without writing, for the snapshot loaded at start. */
    static Set<String> current(Store store, List<Deal> deals, Instant now) {
        // At start the saved snapshot's prices were already recorded, so "at or below" still holds for those deals.
        return lowest(deals, store.seen(deals.stream().map(Deal::id).toList()), now);
    }

    /** MongoDB. */
    static final class Mongo implements Store {
        private final MongoCollection<Document> prices;

        Mongo(MongoDatabase db) { prices = db.getCollection(COLLECTION); }

        @Override public Map<String, Seen> seen(Collection<String> ids) {
            Map<String, Seen> found = new HashMap<>();
            if (ids.isEmpty()) return found;
            for (Document d : prices.find(in("_id", new ArrayList<>(ids))))
                if (d.get("low") instanceof Number low && d.getDate("firstSeen") != null)
                    found.put(d.getString("_id"), new Seen(low.longValue(), d.getDate("firstSeen").toInstant()));
            return found;
        }

        @Override public void record(List<Deal> deals, Map<String, Seen> before, Instant now) {
            List<WriteModel<Document>> writes = new ArrayList<>();
            for (Deal deal : deals) {
                List<Bson> changes = new ArrayList<>(List.of(min("low", deal.saleCents()), set("lastSeen", Date.from(now)),
                        setOnInsert("firstSeen", Date.from(now))));
                var seen = before.get(deal.id());
                if (seen == null || deal.saleCents() < seen.lowCents()) changes.add(set("lowAt", Date.from(now)));
                writes.add(new UpdateOneModel<>(eq("_id", deal.id()), combine(changes), new UpdateOptions().upsert(true)));
            }
            if (!writes.isEmpty()) prices.bulkWrite(writes);
        }
    }
}
