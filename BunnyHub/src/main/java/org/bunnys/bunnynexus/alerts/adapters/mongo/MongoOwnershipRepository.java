package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.client.model.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bunnys.bunnynexus.alerts.application.OwnershipRepository;
import org.bunnys.bunnynexus.alerts.domain.*;
import java.time.Duration;
import java.util.*;
import static com.mongodb.client.model.Filters.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertDatabase.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertSchema.SIMPLE;

/** Ownership records are provisioned explicitly; acquire never grows the collection or resets a generation. */
public final class MongoOwnershipRepository implements OwnershipRepository {
    private final MongoAlertDatabase db;
    public MongoOwnershipRepository(MongoAlertDatabase db) { this.db = Objects.requireNonNull(db); }
    @Override public Optional<Lease> acquire(Resource resource, String token, Duration duration) {
        Objects.requireNonNull(resource); AlertIdentity.token(token); long millis = OwnershipRepository.durationMillis(duration);
        var fields = new Document("leaseToken", literal(token)).append("generation", increment("generation"))
                .append("revision", increment("revision")).append("checkedAt", "$$NOW")
                .append("leaseUntil", new Document("$add", List.of("$$NOW", millis)));
        var guard = and(identity(resource), expiredLease(), lt("generation", Long.MAX_VALUE), lt("revision", Long.MAX_VALUE));
        return update(resource, guard, fields);
    }
    @Override public Optional<Lease> renew(Lease lease, Duration duration) {
        Objects.requireNonNull(lease); long millis = OwnershipRepository.durationMillis(duration);
        var fields = new Document("revision", increment("revision")).append("checkedAt", "$$NOW")
                .append("leaseUntil", new Document("$max", List.of("$leaseUntil", new Document("$add", List.of("$$NOW", millis)))));
        return update(lease.resource(), and(fence(lease), lt("revision", Long.MAX_VALUE)), fields);
    }
    @Override public boolean release(Lease lease) {
        Objects.requireNonNull(lease);
        var fields = new Document("leaseUntil", "$$NOW").append("revision", increment("revision"));
        return db.collection(collection(lease.resource())).updateOne(and(fence(lease), lt("revision", Long.MAX_VALUE)),
                List.of(new Document("$set", fields)), new UpdateOptions().collation(SIMPLE)).getMatchedCount() == 1;
    }
    private Optional<Lease> update(Resource resource, Bson filter, Document fields) {
        var result = db.collection(collection(resource)).findOneAndUpdate(filter, List.of(new Document("$set", fields)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER).collation(SIMPLE));
        if (result == null) return Optional.empty();
        AlertDocuments.version(result);
        if (!id(resource).equals(AlertDocuments.string(result, "_id"))) throw new IllegalStateException("Foreign ownership record.");
        return Optional.of(new Lease(resource, AlertDocuments.string(result, "leaseToken"), AlertDocuments.number(result, "generation"),
                AlertDocuments.instant(result, "checkedAt"), AlertDocuments.instant(result, "leaseUntil")));
    }
    static Bson identity(Resource resource) {
        Bson key = and(eq("_id", id(resource)), eq("schemaVersion", 1));
        return resource instanceof Source source ? and(key, eq("sourceId", source.sourceId().value()), eq("scope", source.scope())) : key;
    }
    static Bson fence(Lease lease) { return and(identity(lease.resource()), eq("leaseToken", lease.token()), eq("generation", lease.generation()), beforeLeaseExpiry()); }
    static String collection(Resource resource) { return resource instanceof Source ? MongoCatalogSchema.SOURCES : MongoRuntimeSchema.OWNERSHIP; }
    static String id(Resource resource) {
        Objects.requireNonNull(resource);
        return resource instanceof Source source ? StableIdentity.hash("source-owner-v1", source.sourceId().value(), source.scope()) : "egress";
    }
}
