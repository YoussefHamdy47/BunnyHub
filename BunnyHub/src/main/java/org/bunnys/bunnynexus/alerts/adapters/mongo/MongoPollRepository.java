package org.bunnys.bunnynexus.alerts.adapters.mongo;

import java.time.Duration;
import java.util.*;
import org.bson.Document;
import com.mongodb.client.model.UpdateOptions;
import org.bunnys.bunnynexus.alerts.application.*;
import static com.mongodb.client.model.Filters.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertDatabase.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertSchema.SIMPLE;

/** Single-document updates serialize schedule and source ownership on the same majority-written record. */
public final class MongoPollRepository implements PollRepository {
    private final MongoAlertDatabase db;
    public MongoPollRepository(MongoAlertDatabase db) { this.db = Objects.requireNonNull(db); }
    @Override public boolean begin(OwnershipRepository.Lease lease, String attempt, Duration interval) {
        PollRepository.validate(lease, attempt, interval);
        var due = new Document("$expr", new Document("$lte", List.of("$nextPollAt", "$$NOW")));
        var filter = and(MongoOwnershipRepository.fence(lease), eq("pollFormat", 1), eq("pollPaused", false),
                lt("revision", Long.MAX_VALUE), due, or(exists("pollAttempt", false), ne("pollGeneration", lease.generation())));
        var set = new Document("pollAttempt", literal(attempt)).append("pollGeneration", lease.generation())
                .append("pollStartedAt", "$$NOW").append("nextPollAt", new Document("$add", List.of("$$NOW", interval.toMillis())))
                .append("revision", increment("revision"));
        return db.collection(MongoCatalogSchema.SOURCES).updateOne(filter, List.of(new Document("$set", set)),
                new UpdateOptions().collation(SIMPLE)).getMatchedCount() == 1;
    }
    @Override public boolean finish(OwnershipRepository.Lease lease, String attempt, Duration interval, Completion result) {
        PollRepository.validate(lease, attempt, interval); Objects.requireNonNull(result);
        var filter = and(MongoOwnershipRepository.fence(lease), eq("pollFormat", 1), eq("pollAttempt", attempt),
                eq("pollGeneration", lease.generation()), lt("revision", Long.MAX_VALUE));
        Object failures = result.outcome() == Outcome.SUCCESS ? 0L
                : new Document("$min", List.of(6L, new Document("$add", List.of("$pollFailures", 1L))));
        var first = new Document("$set", new Document("pollFailures", failures));
        var delay = new Document("$min", List.of(3_600_000L,
                new Document("$multiply", List.of(interval.toMillis(), new Document("$pow", List.of(2L, "$pollFailures"))))));
        var set = new Document("nextPollAt", new Document("$add", List.of("$$NOW", delay)))
                .append("pollFinishedAt", "$$NOW").append("pollOutcome", literal(result.outcome().name()))
                .append("pollPaused", result.outcome() == Outcome.PAUSED).append("pollCandidates", result.candidates())
                .append("pollRejected", result.rejected()).append("revision", increment("revision"));
        return db.collection(MongoCatalogSchema.SOURCES).updateOne(filter,
                List.of(first, new Document("$set", set), new Document("$unset", List.of("pollAttempt", "pollGeneration"))),
                new UpdateOptions().collation(SIMPLE)).getMatchedCount() == 1;
    }
}
