package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.client.ClientSession;
import com.mongodb.client.model.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bunnys.bunnynexus.alerts.application.*;
import org.bunnys.bunnynexus.alerts.domain.*;
import java.time.*;
import java.util.*;
import static com.mongodb.client.model.Filters.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertSchema.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoCatalogSchema.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertDatabase.*;

/** Durable send authorization. No Discord/network transport is invoked from this class. */
public final class MongoDeliveryRepository implements DeliveryRepository {
    private final MongoAlertDatabase db;
    private final MongoDeliveryQueue queue;
    private final String botOwnerId;
    /** Legacy construction remains useful for recovery/receipts but cannot authorize a send. */
    public MongoDeliveryRepository(MongoAlertDatabase db) { this(db, null); }
    public MongoDeliveryRepository(MongoAlertDatabase db, String botOwnerId) {
        this.db = Objects.requireNonNull(db); queue = new MongoDeliveryQueue(db);
        this.botOwnerId = botOwnerId == null ? null : AlertIdentity.snowflake(botOwnerId);
    }
    @Override public Optional<AutomaticSendEngine.Decision> authorize(AuthorizationRequest request, AutomaticSendEngine rule) {
        Objects.requireNonNull(request); Objects.requireNonNull(rule); AlertDocuments.date(request.permissionCheck().validUntil());
        Bson fence = fence(request.jobId(), request.expectedRevision(), request.owner());
        try { return db.transaction(session -> {
            var runtimeFence = MongoOwnershipRepository.fence(request.runtimeLease());
            var runtime = db.collection(MongoRuntimeSchema.OWNERSHIP).findOneAndUpdate(session, runtimeFence,
                    new Document("$inc", new Document("authorizationTouches", 1L)),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER).collation(SIMPLE));
            if (runtime == null) return Optional.empty();
            AlertDocuments.version(runtime);
            Instant runtimeUntil = AlertDocuments.instant(runtime, "leaseUntil");
            Document jobDoc = touchJob(session, fence);
            if (jobDoc == null) return Optional.empty();
            DeliveryJob job = AlertDocuments.delivery(jobDoc); Instant now = AlertDocuments.instant(jobDoc, "checkedAt");
            Document sub = read(session, SUBSCRIPTIONS, job.subscriptionId());
            Document guild = read(session, GUILDS, job.destination().guildId());
            Document destination = read(session, DESTINATIONS, job.destination().destinationId());
            if (sub == null || guild == null || destination == null) {
                DeliveryJob skipped = job.skip(request.owner(), now, DeliveryJob.Reason.CONFIGURATION_CHANGED);
                persistSkipped(session, fence, skipped); return Optional.of(new AutomaticSendEngine.Skipped(skipped));
            }
            Subscription current = AlertDocuments.subscription(sub, guild, destination);
            var eventDoc = read(session, EVENTS, job.key().eventId());
            if (eventDoc == null) throw new IllegalStateException("Delivery event is missing.");
            var event = CatalogDocuments.event(eventDoc);
            String offerId = CatalogDocuments.offerId(event.snapshot().key());
            var offerDoc = read(session, OFFERS, offerId);
            if (offerDoc == null) return Optional.of(new AutomaticSendEngine.Blocked(AutomaticSendEngine.BlockReason.REFRESH_EVIDENCE));
            Offer offer = CatalogDocuments.offer(offerDoc);
            var proof = request.permissionCheck();
            boolean permitted = proof.destination().equals(job.destination()) && proof.roles().equals(request.payload().approvedRoles());
            var decision = rule.authorize(new AutomaticSendEngine.Snapshot(job, current, offer, event.id(),
                    new AlertIdentity.FreeGameKey(event.snapshot().key()), event.observedAt(), now, permitted,
                    proof.validUntil().isBefore(runtimeUntil) ? proof.validUntil() : runtimeUntil), request.owner(), request.payload());
            if (decision instanceof AutomaticSendEngine.Blocked) return Optional.of(decision);
            if (decision instanceof AutomaticSendEngine.Skipped skipped) {
                persistSkipped(session, fence, skipped.job()); return Optional.of(decision);
            }
            var release = MongoOwnerReviewRepository.releaseFor(session, db, botOwnerId, offer, request.payload(), now);
            if (release == null) return Optional.of(new AutomaticSendEngine.Blocked(AutomaticSendEngine.BlockReason.OWNER_RELEASE_REQUIRED));
            Instant releaseUntil = AlertDocuments.instant(release, "releaseUntil");
            Instant boundedUntil = ((AutomaticSendEngine.Sending) decision).job().attempt().orElseThrow().validUntil();
            if (releaseUntil.isBefore(boundedUntil)) boundedUntil = releaseUntil;
            decision = rule.authorize(new AutomaticSendEngine.Snapshot(job, current, offer, event.id(),
                    new AlertIdentity.FreeGameKey(event.snapshot().key()), event.observedAt(), now, permitted, boundedUntil),
                    request.owner(), request.payload());
            var sending = ((AutomaticSendEngine.Sending) decision).job();
            // Snapshot reads alone do not serialize a concurrent disable/edit. Touch every shared guard.
            touchGuard(session, GUILDS, guild, "revision");
            touchGuard(session, DESTINATIONS, destination, "revision");
            touchGuard(session, SUBSCRIPTIONS, sub, "revision");
            touchGuard(session, OFFERS, offerDoc, "contentRevision");
            if (!MongoOwnerReviewRepository.reserve(session, db, release)) throw new Conflict();
            var attempt = sending.attempt().orElseThrow();
            db.collection(ATTEMPTS).insertOne(session, AlertDocuments.attempt(attempt).append("jobId", job.id()).append("outcome", "SENDING"));
            Bson finalFence = and(fence, new Document("$expr", new Document("$lt", List.of("$$NOW", AlertDocuments.date(attempt.validUntil())))));
            if (db.collection(DELIVERIES).replaceOne(session, finalFence, AlertDocuments.delivery(sending),
                    new ReplaceOptions().collation(SIMPLE)).getMatchedCount() != 1) throw new Conflict();
            if (db.collection(MongoRuntimeSchema.OWNERSHIP).updateOne(session, runtimeFence,
                    new Document("$inc", new Document("authorizationTouches", 1L)), new UpdateOptions().collation(SIMPLE)).getMatchedCount() != 1)
                throw new Conflict();
            return Optional.of(decision);
        }); } catch (Conflict lost) { return Optional.empty(); }
    }
    @Override public Optional<DeliveryJob> defer(String id, long revision, DeliveryJob.Lease owner, Instant retryAt) {
        AlertIdentity.token(id); Objects.requireNonNull(owner); AlertDocuments.date(retryAt);
        if (revision < 1) throw new IllegalArgumentException("Invalid revision.");
        Bson fence = fence(id, revision, owner);
        try { return db.transaction(session -> {
            Document d = touchJob(session, fence); if (d == null) return Optional.empty();
            DeliveryJob next = AlertDocuments.delivery(d).defer(owner, AlertDocuments.instant(d, "checkedAt"), retryAt);
            if (db.collection(DELIVERIES).replaceOne(session, fence, AlertDocuments.delivery(next),
                    new ReplaceOptions().collation(SIMPLE)).getMatchedCount() != 1) throw new Conflict();
            return Optional.of(next);
        }); } catch (Conflict lost) { return Optional.empty(); }
    }
    private Document read(ClientSession session, String collection, String id) {
        return db.collection(collection).find(session, eq("_id", id)).collation(SIMPLE).first();
    }
    private Document touchJob(ClientSession session, Bson fence) {
        return db.collection(DELIVERIES).findOneAndUpdate(session, fence, List.of(new Document("$set", new Document("checkedAt", "$$NOW"))),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER).collation(SIMPLE));
    }
    private void touchGuard(ClientSession session, String collection, Document guard, String revisionField) {
        Bson filter = and(eq("_id", AlertDocuments.string(guard, "_id")), eq("schemaVersion", 1), eq(revisionField, AlertDocuments.number(guard, revisionField)));
        if (db.collection(collection).updateOne(session, filter, new Document("$inc", new Document("authorizationTouches", 1L)),
                new UpdateOptions().collation(SIMPLE)).getMatchedCount() != 1) throw new Conflict();
    }
    private void persistSkipped(ClientSession session, Bson fence, DeliveryJob job) {
        if (db.collection(BACKLOG).updateOne(session, and(eq("_id", "delivery"), eq("schemaVersion", 1), gte("pendingJobs", 1L)),
                new Document("$inc", new Document("pendingJobs", -1L).append("revision", 1L)), new UpdateOptions().collation(SIMPLE)).getMatchedCount() != 1)
            throw new IllegalStateException("Backlog count is inconsistent.");
        if (db.collection(DELIVERIES).replaceOne(session, fence, AlertDocuments.delivery(job),
                new ReplaceOptions().collation(SIMPLE)).getMatchedCount() != 1) throw new Conflict();
    }
    private static Bson fence(String id, long revision, DeliveryJob.Lease owner) {
        return and(eq("_id", id), eq("schemaVersion", 1), eq("state", "LEASED"), eq("revision", revision),
                eq("leaseToken", owner.token()), eq("generation", owner.generation()), beforeLeaseExpiry());
    }
    @Override public List<DeliveryJob> due(DeliveryJob.State state, String guild, int limit) { return queue.due(state, guild, limit); }
    @Override public Optional<DeliveryJob> claim(String id, String token, Duration duration) { return queue.claim(id, token, duration); }
    @Override public Optional<DeliveryJob> recoverExpired(String id, long revision) { return queue.recoverExpired(id, revision); }
    @Override public Optional<DeliveryJob> recordOutcome(String id, long revision, DeliveryJob.Lease owner, String attemptId,
                                                       DeliveryJob.Outcome outcome, Optional<Instant> retryAt) {
        return queue.recordOutcome(id, revision, owner, attemptId, outcome, retryAt);
    }
    private static final class Conflict extends RuntimeException {}
}
