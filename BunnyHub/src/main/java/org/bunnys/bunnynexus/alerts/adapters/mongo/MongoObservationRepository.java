package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.client.model.*;
import org.bson.Document;
import org.bunnys.bunnynexus.alerts.application.ObservationRepository;
import org.bunnys.bunnynexus.alerts.domain.*;
import java.util.*;
import static com.mongodb.client.model.Filters.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertSchema.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoCatalogSchema.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertDatabase.*;

/** Atomic canonical observation/event/plan commit. Source certification and ordering evidence are caller obligations. */
public final class MongoObservationRepository implements ObservationRepository {
    private final MongoAlertDatabase db;
    public MongoObservationRepository(MongoAlertDatabase db) { this.db = Objects.requireNonNull(db); }
    @Override public Result commit(Commit command, FreeGamePolicy policy) {
        Objects.requireNonNull(command); Objects.requireNonNull(policy);
        Document normalized = CatalogDocuments.offer(command.offer()); // Validate before any transaction.
        AlertDocuments.date(command.observedAt());
        try { return db.transaction(session -> {
            var source = command.sourceLease();
            var sourceGuard = and(eq("sourceId", source.source().value()), eq("scope", source.scope()), eq("schemaVersion", 1),
                    eq("leaseToken", source.token()), eq("generation", source.generation()), beforeLeaseExpiry());
            var state = db.collection(SOURCES).findOneAndUpdate(session, sourceGuard,
                    List.of(new Document("$set", new Document("checkedAt", "$$NOW"))),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER).collation(SIMPLE));
            if (state == null) return Result.STALE_OBSERVATION;
            var now = AlertDocuments.instant(state, "checkedAt");
            if (command.observedAt().isAfter(now)) throw new IllegalArgumentException("Future observation.");
            String offerId = CatalogDocuments.offerId(command.offer().key());
            String mappingId = CatalogDocuments.id("mapping-v1", source.source().value(), source.scope(), command.offer().evidence().sourceItemId(), offerId);
            var mapping = db.collection(MAPPINGS).find(session, eq("_id", mappingId)).collation(SIMPLE).first();
            if (mapping != null) {
                AlertDocuments.version(mapping);
                if (AlertDocuments.number(mapping, "sourceOrder") >= command.sourceOrder()) return Result.STALE_OBSERVATION;
            }
            var old = db.collection(OFFERS).find(session, eq("_id", offerId)).collation(SIMPLE).first();
            Offer previous = old == null ? null : CatalogDocuments.offer(old);
            if (old == null ? command.expectedContentRevision() != 0 :
                    previous.contentRevision() != command.expectedContentRevision()
                            || !previous.key().equals(command.offer().key())
                            || !previous.editionId().equals(command.offer().editionId())
                            || previous.verifiedAt().isAfter(command.offer().verifiedAt())) return Result.STALE_OBSERVATION;
            var eventDoc = db.collection(EVENTS).find(session, and(eq("offerId", offerId), eq("kind", "FREE_GAME_AVAILABLE"))).collation(SIMPLE).first();
            var event = eventDoc == null ? new CatalogDocuments.Event(command.eventId(), command.offer(), command.observedAt()) : CatalogDocuments.event(eventDoc);
            if (!event.snapshot().key().equals(command.offer().key())) throw new IllegalStateException("Event identity collision.");
            boolean eligible = policy.evaluate(command.offer(), now).eligible();
            var plan = eligible ? db.collection(PLANS).find(session, and(eq("eventId", event.id()), eq("kind", "AUTOMATIC_FREE_GAME"))).collation(SIMPLE).first() : null;
            if (eligible && plan == null) {
                var capacity = and(eq("_id", "outbox"), eq("schemaVersion", 1), eq("paused", false),
                        new Document("$expr", new Document("$lt", List.of("$pendingPlans", "$maxPlans"))));
                if (db.collection(BACKLOG).updateOne(session, capacity, new Document("$inc", new Document("pendingPlans", 1L).append("revision", 1L)), new UpdateOptions().collation(SIMPLE)).getMatchedCount() != 1)
                    return Result.BACKPRESSURE;
            }
            if (old == null) db.collection(OFFERS).insertOne(session, normalized);
            else if (db.collection(OFFERS).replaceOne(session, and(eq("_id", offerId), eq("contentRevision", command.expectedContentRevision())), normalized,
                    new ReplaceOptions().collation(SIMPLE)).getMatchedCount() != 1) throw new Conflict();
            Document nextMapping = AlertDocuments.base(mappingId).append("sourceId", source.source().value()).append("scope", source.scope())
                    .append("sourceItemId", command.offer().evidence().sourceItemId()).append("offerId", offerId)
                    .append("sourceOrder", command.sourceOrder()).append("identityRuleVersion", command.offer().evidence().identityRuleVersion())
                    .append("revision", mapping == null ? 1L : Math.incrementExact(AlertDocuments.number(mapping, "revision")));
            if (mapping == null) db.collection(MAPPINGS).insertOne(session, nextMapping);
            else if (db.collection(MAPPINGS).replaceOne(session, and(eq("_id", mappingId), eq("revision", AlertDocuments.number(mapping, "revision"))),
                    nextMapping, new ReplaceOptions().collation(SIMPLE)).getMatchedCount() != 1) throw new Conflict();
            if (eligible && eventDoc == null) db.collection(EVENTS).insertOne(session, CatalogDocuments.event(event));
            if (eligible && plan == null) db.collection(PLANS).insertOne(session, AlertDocuments.plan(FanoutPlan.automatic(command.automaticPlanId(),
                    event.id(), new AlertIdentity.FreeGameKey(command.offer().key()), event.observedAt()).counted()));
            if (db.collection(SOURCES).updateOne(session, sourceGuard, new Document("$inc", new Document("revision", 1L)), new UpdateOptions().collation(SIMPLE)).getMatchedCount() != 1) throw new Conflict();
            return eligible && eventDoc == null ? Result.EVENT_CREATED : Result.OBSERVATION_UPDATED;
        }); } catch (Conflict stale) { return Result.STALE_OBSERVATION; }
    }
    private static final class Conflict extends RuntimeException {}
}
