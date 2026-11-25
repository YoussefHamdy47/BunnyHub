package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

/**
 * Where each server's latest public role picker ("Never miss your next free game") was posted, so it can be rebuilt
 * in place when the roles it offers change (for example when a deals board gets a ping role). One per server: posting
 * a new picker replaces the remembered one. Blocking; never call on the gateway thread.
 */
public final class FreebieRolePickers {
    public static final String COLLECTION = "FreebieRolePickers";

    public record Picker(String guildId, String channelId, String messageId) {}

    private final MongoCollection<Document> pickers;

    public FreebieRolePickers(MongoDatabase db) { pickers = db.getCollection(COLLECTION); }

    public void posted(String guildId, String channelId, String messageId, Instant now) {
        pickers.replaceOne(eq("_id", guildId), new Document("_id", guildId).append("channelId", channelId)
                .append("messageId", messageId).append("postedAt", Date.from(now)), new ReplaceOptions().upsert(true));
    }

    public Optional<Picker> find(String guildId) {
        Document doc = pickers.find(eq("_id", guildId)).first();
        if (doc == null || doc.getString("channelId") == null || doc.getString("messageId") == null) return Optional.empty();
        return Optional.of(new Picker(guildId, doc.getString("channelId"), doc.getString("messageId")));
    }

    /** The picker message is gone; only forgets it when it is still the remembered one. */
    public void gone(String guildId, String messageId) {
        pickers.deleteOne(and(eq("_id", guildId), eq("messageId", messageId)));
    }

    /** The bot left the server. */
    public void forget(String guildId) { pickers.deleteOne(eq("_id", guildId)); }
}
