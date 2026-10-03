package org.bunnys.handler.database;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.conversions.Bson;
import org.bunnys.handler.utils.InteractionErrors;

/** Static access to the shared database, with revision-guarded replacement for versioned documents. */
public final class DB {
    private static volatile MongoManager manager;

    private DB() {}

    public static void init(MongoManager mongoManager) {
        manager = mongoManager;
    }

    public static MongoDatabase getDatabase() {
        checkConnection();
        return manager.getDatabase();
    }

    public static <T> MongoCollection<T> getCollection(Class<T> clazz, String collectionName) {
        checkConnection();
        return manager.getCollection(clazz, collectionName);
    }

    public static ClientSession startSession() {
        checkConnection();
        return manager.startSession();
    }

    private static void checkConnection() {
        if (manager == null || manager.getDatabase() == null)
            throw new IllegalStateException("[Database] Critical: Attempted an operation before MongoDB was initialized!");
    }

    /** The first document matching {@code filter}, or null. */
    public static <T> T findOne(Class<T> clazz, String collectionName, Bson filter) {
        return getCollection(clazz, collectionName).find(filter).first();
    }

    /**
     * Replaces the document matching {@code filter}. A {@link VersionedDocument} is only written when its stored
     * revision still matches (and is then bumped), so a concurrent edit fails instead of being overwritten; other
     * documents are upserted.
     */
    public static <T> void save(Class<T> clazz, String collectionName, Bson filter, T document) {
        checkConnection();
        replace(null, clazz, collectionName, filter, document);
    }

    /** As {@link #save}, inside a caller-owned transaction when {@code session} is non-null. */
    public static <T> void replace(ClientSession session, Class<T> type, String collection, Bson filter, T document) {
        var target = getCollection(type, collection);
        if (document instanceof VersionedDocument versioned) {
            Long previous = versioned.getRevision();
            versioned.setRevision(previous == null ? 1L : Math.addExact(previous, 1L));
            try {
                Bson guarded = Filters.and(filter, Filters.eq("revision", previous));
                var result = session == null ? target.replaceOne(guarded, document) : target.replaceOne(session, guarded, document);
                if (result.getMatchedCount() == 0)
                    throw new InteractionErrors.StateFailure("Your data changed during this action. Please try again.");
            } catch (RuntimeException error) {
                versioned.setRevision(previous);
                throw error;
            }
        } else if (session == null) {
            target.replaceOne(filter, document, new ReplaceOptions().upsert(true));
        } else {
            target.replaceOne(session, filter, document, new ReplaceOptions().upsert(true));
        }
    }
}
