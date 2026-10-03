package org.bunnys.bunnynexus.timers.services;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import org.bson.BsonDocument;
import org.bson.BsonDocumentWriter;
import org.bson.Document;
import org.bson.codecs.EncoderContext;
import org.bunnys.database.models.timers.Semester;
import org.bunnys.database.models.timers.TimerData;
import org.bunnys.database.models.user.BunnyUser;
import org.bunnys.handler.database.DB;
import java.util.Date;
import java.util.List;

/** The timer feature's multi-document writes, each in one transaction. */
final class TimerStore {
    private TimerStore() {}

    /** Commit account rewards and timer state together; never leave half a completed session. */
    static void saveProgress(String userId, BunnyUser user, TimerData timer) {
        saveProgress(userId, user, timer, null);
    }

    static void saveProgress(String userId, BunnyUser user, TimerData timer, Semester archive) {
        Long userRevision = user.getRevision();
        Long timerRevision = timer.getRevision();
        try (var session = DB.startSession()) {
            session.withTransaction(() -> {
                // The driver may retry a transaction. Reuse the original expected revisions.
                user.setRevision(userRevision);
                timer.setRevision(timerRevision);
                DB.replace(session, BunnyUser.class, "BunnyUsers", Filters.eq("userID", userId), user);
                DB.replace(session, TimerData.class, "TimerData", Filters.eq("account.userID", userId), timer);
                if (archive != null)
                    DB.getCollection(Document.class, "SemesterHistory").insertOne(session, new Document("userID", userId)
                            .append("archivedAt", new Date()).append("semester", encode(archive)));
                return null;
            });
        } catch (RuntimeException error) {
            user.setRevision(userRevision);
            timer.setRevision(timerRevision);
            throw error;
        }
    }

    private static Document encode(Semester semester) {
        var encoded = new BsonDocument();
        DB.getDatabase().getCodecRegistry().get(Semester.class)
                .encode(new BsonDocumentWriter(encoded), semester, EncoderContext.builder().build());
        return Document.parse(encoded.toJson());
    }

    /** Insert-only upsert repairs partially registered accounts without overwriting existing records. */
    static void ensureAccount(String userId) {
        try (var session = DB.startSession()) {
            session.withTransaction(() -> {
                DB.getCollection(Document.class, "BunnyUsers").updateOne(session, Filters.eq("userID", userId),
                        new Document("$setOnInsert", new Document("userID", userId)
                                .append("Rank", 0).append("RP", 0L).append("Subjects", List.of())),
                        new UpdateOptions().upsert(true));
                DB.getCollection(Document.class, "TimerData").updateOne(session, Filters.eq("account.userID", userId),
                        new Document("$setOnInsert", new Document("account",
                                new Document("userID", userId).append("lifetimeTime", 0.0))),
                        new UpdateOptions().upsert(true));
                return null;
            });
        }
    }
}
