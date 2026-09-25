package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.*;
import com.mongodb.client.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Explicit injected client; never opens a connection or reads the bot's environment by itself. */
public final class MongoAlertDatabase {
    private final MongoClient client;
    private final MongoDatabase database;
    private final long timeoutMillis;
    private final TransactionOptions transactionOptions;
    public MongoAlertDatabase(MongoClient client, String databaseName, Duration operationBudget) {
        this.client = Objects.requireNonNull(client);
        timeoutMillis = positiveMillis(operationBudget);
        database = client.getDatabase(org.bunnys.bunnynexus.alerts.domain.AlertIdentity.token(databaseName))
                .withTimeout(timeoutMillis, TimeUnit.MILLISECONDS).withReadPreference(ReadPreference.primary())
                .withWriteConcern(WriteConcern.MAJORITY).withReadConcern(ReadConcern.MAJORITY);
        transactionOptions = TransactionOptions.builder().readConcern(ReadConcern.SNAPSHOT)
                .writeConcern(WriteConcern.MAJORITY).readPreference(ReadPreference.primary())
                .timeout(timeoutMillis, TimeUnit.MILLISECONDS).build();
    }
    MongoCollection<Document> collection(String name) { return database.getCollection(name); }
    <T> T transaction(Function<ClientSession, T> action) {
        try (ClientSession session = client.startSession(ClientSessionOptions.builder()
                .defaultTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build())) {
            // Exceptions, including unresolved commit results, propagate. Never turn them into success/conflict.
            return session.withTransaction(() -> action.apply(session), transactionOptions);
        }
    }
    static long positiveMillis(Duration duration) {
        Objects.requireNonNull(duration);
        long millis = duration.toMillis();
        if (duration.isNegative() || millis < 1) throw new IllegalArgumentException("Finite positive millisecond budget required.");
        return millis;
    }
    static Bson beforeLeaseExpiry() { return new Document("$expr", new Document("$gt", List.of("$leaseUntil", "$$NOW"))); }
    static Bson expiredLease() { return new Document("$expr", new Document("$lte", List.of("$leaseUntil", "$$NOW"))); }
    static Document increment(String field) { return new Document("$add", List.of("$" + field, 1L)); }
    static Document literal(String value) { return new Document("$literal", value); }
}
