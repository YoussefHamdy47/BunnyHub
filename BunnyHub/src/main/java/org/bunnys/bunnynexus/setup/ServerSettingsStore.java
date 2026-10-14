package org.bunnys.bunnynexus.setup;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.bunnys.bunnynexus.setup.ServerSettings.Greeting;
import org.bunnys.handler.database.DB;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.mongodb.client.model.Filters.eq;

/**
 * {@link ServerSettings} in MongoDB, one document per server ({@code _id} = guild id), behind a small cache so that
 * member joins and the setup panel's buttons don't wait on the database.
 */
public final class ServerSettingsStore {
    public static final String COLLECTION = "ServerSettings";
    private static volatile ServerSettingsStore instance;

    private final MongoCollection<Document> collection;
    private final Cache<String, ServerSettings> cache = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterAccess(Duration.ofHours(1)).build();

    public ServerSettingsStore(MongoDatabase db) { collection = db.getCollection(COLLECTION); }

    /** The shared store, created on first use; empty while the database is unavailable. */
    public static Optional<ServerSettingsStore> current() {
        ServerSettingsStore store = instance;
        if (store != null) return Optional.of(store);
        synchronized (ServerSettingsStore.class) {
            if (instance == null) {
                try { instance = new ServerSettingsStore(DB.getDatabase()); }
                catch (RuntimeException offline) {
                    BunnyLog.warning("[Setup] Server settings unavailable | " + FailureDiagnostics.describe(offline));
                    return Optional.empty();
                }
            }
            return Optional.of(instance);
        }
    }

    public ServerSettings get(String guildId) {
        return cache.get(guildId, id -> {
            Document doc = collection.find(eq("_id", id)).first();
            return doc == null ? ServerSettings.empty() : read(doc);
        });
    }

    /** Cached only: null when this server's settings aren't in memory. */
    public ServerSettings cached(String guildId) { return cache.getIfPresent(guildId); }

    public void save(String guildId, ServerSettings settings, String actorId) {
        if (settings.isEmpty()) {
            collection.deleteOne(eq("_id", guildId));
        } else {
            collection.replaceOne(eq("_id", guildId), write(guildId, settings).append("updatedBy", actorId)
                    .append("updatedAt", Instant.now()), new ReplaceOptions().upsert(true));
        }
        cache.put(guildId, settings);
    }

    /** The bot left the server: nothing of it is kept. */
    public void remove(String guildId) {
        collection.deleteOne(eq("_id", guildId));
        cache.invalidate(guildId);
    }

    static ServerSettings read(Document doc) {
        List<String> roles = new ArrayList<>();
        Object stored = doc.get("joinRoles");
        if (stored instanceof List<?> list) for (Object id : list) if (id instanceof String s) roles.add(s);
        return new ServerSettings(greeting(doc.get("welcome", Document.class)), greeting(doc.get("goodbye", Document.class)),
                roles.stream().distinct().limit(ServerSettings.MAX_JOIN_ROLES).toList());
    }

    static Document write(String guildId, ServerSettings settings) {
        return new Document("_id", guildId)
                .append("welcome", greeting(settings.welcome()))
                .append("goodbye", greeting(settings.goodbye()))
                .append("joinRoles", settings.joinRoles());
    }

    private static Greeting greeting(Document doc) {
        return doc == null ? new Greeting(null, null) : new Greeting(doc.getString("channelId"), doc.getString("message"));
    }

    private static Document greeting(Greeting greeting) {
        return new Document("channelId", greeting.channelId()).append("message", greeting.message());
    }
}
