package org.bunnys.handler.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;

public class DB {
    private static volatile MongoManager manager;

    public static com.mongodb.client.MongoDatabase getDatabase() {
        checkConnection();
        return manager.getDatabase();
    }

    public static void init(MongoManager mongoManager) {
        manager = mongoManager;
    }

    private static void checkConnection() {
        if (manager == null || manager.getDatabase() == null)
            throw new IllegalStateException(
                    "[Database] Critical: Attempted an operation before MongoDB was initialized!");
    }

    public static <T> MongoCollection<T> getCollection(Class<T> clazz, String collectionName) {
        checkConnection();
        return manager.getCollection(clazz, collectionName);
    }

    /**
     * Find a single document by its explicit String ID.
     * Mongoose: Model.findById(id)
     */
    public static <T> T findById(Class<T> clazz, String collectionName, String id) {
        checkConnection();
        return getCollection(clazz, collectionName).find(Filters.eq("_id", id)).first();
    }

    /**
     * Find a single document matching a custom Bson filter queries.
     * Mongoose: Model.findOne({ status: "active" })
     */
    public static <T> T findOne(Class<T> clazz, String collectionName, Bson filter) {
        checkConnection();
        return getCollection(clazz, collectionName).find(filter).first();
    }

    /**
     * Find multiple documents matching a custom filter queries.
     * Mongoose: Model.find({ type: "timer" })
     */
    public static <T> List<T> findMany(Class<T> clazz, String collectionName, Bson filter) {
        checkConnection();
        List<T> results = new ArrayList<>();
        getCollection(clazz, collectionName).find(filter).into(results);
        return results;
    }

    /**
     * Saves or Completely Replaces a POJO document by ID (Upsert).
     * Mongoose: doc.save()
     */
    public static <T> void save(Class<T> clazz, String collectionName, String id, T document) {
        checkConnection();
        save(clazz, collectionName, Filters.eq("_id", id), document);
    }

    /**
     * Saves or Completely Replaces a POJO document using a custom Bson filter
     * (Upsert).
     * Mongoose: Model.updateOne({ userID: "123" }, doc, { upsert: true })
     */
    public static <T> void save(Class<T> clazz, String collectionName, Bson filter, T document) {
        checkConnection();
        replace(null, clazz, collectionName, filter, document);
    }

    private static <T> void replace(com.mongodb.client.ClientSession session, Class<T> type,
                                    String collection, Bson filter, T document) {
        var target = getCollection(type, collection);
        if (document instanceof VersionedDocument versioned) {
            Long previous = versioned.getRevision();
            versioned.setRevision(previous == null ? 1L : Math.addExact(previous, 1L));
            try {
                Bson guarded = Filters.and(filter, Filters.eq("revision", previous));
                var result = session == null ? target.replaceOne(guarded, document)
                        : target.replaceOne(session, guarded, document);
                if (result.getMatchedCount() == 0)
                    throw new org.bunnys.handler.utils.InteractionErrors.StateFailure("Your data changed during this action. Please try again.");
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

    /** Commit account rewards and timer state together; never leave half a completed session. */
    public static void saveProgress(String userId, org.bunnys.database.models.user.BunnyUser user,
                                   org.bunnys.database.models.timers.TimerData timer) {
        saveProgress(userId, user, timer, null);
    }

    public static void saveProgress(String userId, org.bunnys.database.models.user.BunnyUser user,
                                   org.bunnys.database.models.timers.TimerData timer,
                                   org.bunnys.database.models.timers.Semester archive) {
        checkConnection();
        Long userRevision = user.getRevision();
        Long timerRevision = timer.getRevision();
        try (var session = manager.startSession()) {
            session.withTransaction(() -> {
                // The driver may retry a transaction. Reuse the original expected revisions.
                user.setRevision(userRevision);
                timer.setRevision(timerRevision);
                replace(session, org.bunnys.database.models.user.BunnyUser.class, "BunnyUsers",
                        Filters.eq("userID", userId), user);
                replace(session, org.bunnys.database.models.timers.TimerData.class, "TimerData",
                        Filters.eq("account.userID", userId), timer);
                if (archive != null) {
                    var registry = manager.getDatabase().getCodecRegistry();
                    var encoded = new org.bson.BsonDocument();
                    registry.get(org.bunnys.database.models.timers.Semester.class).encode(
                            new org.bson.BsonDocumentWriter(encoded), archive,
                            org.bson.codecs.EncoderContext.builder().build());
                    getCollection(org.bson.Document.class, "SemesterHistory").insertOne(session,
                            new org.bson.Document("userID", userId).append("archivedAt", new java.util.Date())
                                    .append("semester", org.bson.Document.parse(encoded.toJson())));
                }
                return null;
            });
        } catch (RuntimeException error) {
            user.setRevision(userRevision);
            timer.setRevision(timerRevision);
            throw error;
        }
    }

    /** Insert-only upsert repairs partially registered accounts without overwriting existing records. */
    public static void ensureAccount(String userId) {
        checkConnection();
        try (var session = manager.startSession()) {
            session.withTransaction(() -> {
                var users = getCollection(org.bson.Document.class, "BunnyUsers");
                users.updateOne(session, Filters.eq("userID", userId),
                        new org.bson.Document("$setOnInsert", new org.bson.Document("userID", userId)
                                .append("Rank", 0).append("RP", 0L).append("Subjects", List.of())),
                        new com.mongodb.client.model.UpdateOptions().upsert(true));
                var timers = getCollection(org.bson.Document.class, "TimerData");
                timers.updateOne(session, Filters.eq("account.userID", userId),
                        new org.bson.Document("$setOnInsert", new org.bson.Document("account",
                                new org.bson.Document("userID", userId).append("lifetimeTime", 0.0))),
                        new com.mongodb.client.model.UpdateOptions().upsert(true));
                return null;
            });
        }
    }

    /**
     * Delete a single document by its unique ID.
     */
    public static <T> void deleteById(Class<T> clazz, String collectionName, String id) {
        checkConnection();
        getCollection(clazz, collectionName).deleteOne(Filters.eq("_id", id));
    }

    /**
     * Delete multiple documents matching a filter statement.
     * Mongoose: Model.deleteMany({ expired: true })
     */
    public static <T> long deleteMany(Class<T> clazz, String collectionName, Bson filter) {
        checkConnection();
        return getCollection(clazz, collectionName).deleteMany(filter).getDeletedCount();
    }
}
