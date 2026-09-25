package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.client.model.*;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bunnys.bunnynexus.alerts.application.*;
import org.bunnys.bunnynexus.alerts.domain.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoTestSupport.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoCatalogSchema.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertSchema.*;

class MongoAuthorizationTest {
    @Test void persistedAttemptCannotOutliveTheCurrentDeliveryLease() {
        var h = configured();
        var shortJob = DeliveryJob.ready("job", new AlertIdentity.DeliveryKey("event", "200"), DEST, "sub", NOW)
                .claim("owner", NOW, NOW.plusSeconds(3));
        when(h.collection(DELIVERIES).findOneAndUpdate(eq(h.session), any(Bson.class), anyList(), any(FindOneAndUpdateOptions.class)))
                .thenReturn(checked(AlertDocuments.delivery(shortJob), NOW));
        var result = assertInstanceOf(AutomaticSendEngine.Sending.class, authorize(h, request()).orElseThrow());
        assertEquals(NOW.plusSeconds(3), result.job().attempt().orElseThrow().validUntil());
        var persisted = ArgumentCaptor.forClass(Document.class);
        verify(h.collection(ATTEMPTS)).insertOne(eq(h.session), persisted.capture());
        assertEquals(Date.from(NOW.plusSeconds(3)), persisted.getValue().getDate("validUntil"));
    }
    @Test void lostRuntimeCannotAuthorizeAndRuntimeDeadlineBoundsTheAttempt() {
        var h = configured();
        when(h.collection(MongoRuntimeSchema.OWNERSHIP).findOneAndUpdate(eq(h.session), any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class))).thenReturn(null);
        assertTrue(authorize(h, request()).isEmpty()); verifyNoInteractions(h.collection(ATTEMPTS));
        var shortLease = configured();
        when(shortLease.collection(MongoRuntimeSchema.OWNERSHIP).findOneAndUpdate(eq(shortLease.session), any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(new Document("_id", "egress").append("schemaVersion", 1).append("leaseUntil", Date.from(NOW.plusSeconds(5))));
        var sending = assertInstanceOf(AutomaticSendEngine.Sending.class, authorize(shortLease, request()).orElseThrow());
        assertEquals(NOW.plusSeconds(5), sending.job().attempt().orElseThrow().validUntil());
    }
    @Test void runtimeExpiryAtFinalFenceNeverReturnsSending() {
        var h = configured();
        when(h.collection(MongoRuntimeSchema.OWNERSHIP).updateOne(eq(h.session), any(Bson.class), any(Bson.class), any(UpdateOptions.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        assertTrue(authorize(h, request()).isEmpty());
    }
    @Test void authorizedAttemptIsCompatibleWithQueueReceiptWriter() {
        var h = configured();
        var sending = ((AutomaticSendEngine.Sending) authorize(h, request()).orElseThrow()).job();
        when(h.collection(DELIVERIES).findOneAndUpdate(eq(h.session), any(Bson.class), anyList(), any(FindOneAndUpdateOptions.class)))
                .thenReturn(checked(AlertDocuments.delivery(sending), NOW.plusSeconds(1)));
        when(h.collection(ATTEMPTS).updateOne(eq(h.session), any(Bson.class), any(Bson.class), argThat((UpdateOptions o) -> SIMPLE.equals(o.getCollation())))).thenReturn(UpdateResult.acknowledged(1, 1L, null));
        when(h.collection(DELIVERIES).replaceOne(eq(h.session), any(Bson.class), any(Document.class), argThat((ReplaceOptions o) -> SIMPLE.equals(o.getCollation())))).thenReturn(UpdateResult.acknowledged(1, 1L, null));
        var sent = new MongoDeliveryRepository(h.db).recordOutcome(sending.id(), sending.revision(), sending.lease().orElseThrow(),
                sending.attempt().orElseThrow().id(), new DeliveryJob.Accepted("500"), Optional.empty()).orElseThrow();
        assertEquals(DeliveryJob.State.SENT, sent.state()); assertEquals("500", sent.messageId().orElseThrow());
        assertEquals(sending.key(), sent.key()); assertEquals(sending.attempt(), sent.attempt());
    }
    private DeliveryJob job() { return DeliveryJob.ready("job", new AlertIdentity.DeliveryKey("event", "200"), DEST, "sub", NOW).claim("owner", NOW, NOW.plusSeconds(60)); }
    private DeliveryRepository.AuthorizationRequest request() {
        var j = job(); return new DeliveryRepository.AuthorizationRequest(j.id(), j.revision(), j.lease().orElseThrow(),
                new AutomaticSendEngine.PreparedPayload(MongoCatalogTest.offer(1).key(), "sub", DEST, "attempt", "nonce", 1, 1, "v1", "a".repeat(64), Set.of("300"), MongoOwnerReviewTest.material(MongoCatalogTest.offer(1))),
                new DeliveryRepository.PermissionCheck(DEST, Set.of("300"), NOW.plusSeconds(20)), runtimeLease());
    }
    private OwnershipRepository.Lease runtimeLease() { return new OwnershipRepository.Lease(new OwnershipRepository.Runtime(), "process", 1, NOW, NOW.plusSeconds(60)); }
    private MongoTestSupport configured() {
        var h = new MongoTestSupport();
        when(h.collection(MongoOwnerReviewSchema.REVIEWS).find(eq(h.session), any(Bson.class)))
                .thenAnswer(ignored -> find(MongoOwnerReviewTest.released(MongoCatalogTest.offer(1))));
        when(h.collection(MongoOwnerReviewSchema.REVIEWS).updateOne(eq(h.session), any(Bson.class), any(Bson.class), any(UpdateOptions.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        when(h.collection(MongoRuntimeSchema.OWNERSHIP).findOneAndUpdate(eq(h.session), any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(new Document("_id", "egress").append("schemaVersion", 1).append("leaseUntil", Date.from(NOW.plusSeconds(60))));
        when(h.collection(MongoRuntimeSchema.OWNERSHIP).updateOne(eq(h.session), any(Bson.class), any(Bson.class), any(UpdateOptions.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        when(h.collection(DELIVERIES).findOneAndUpdate(eq(h.session), any(Bson.class), anyList(), any(FindOneAndUpdateOptions.class)))
                .thenReturn(checked(AlertDocuments.delivery(job()), NOW));
        when(h.collection(SUBSCRIPTIONS).find(eq(h.session), any(Bson.class))).thenAnswer(ignored -> find(AlertDocuments.subscription(subscription())));
        when(h.collection(GUILDS).find(eq(h.session), any(Bson.class))).thenAnswer(ignored -> find(guild()));
        when(h.collection(DESTINATIONS).find(eq(h.session), any(Bson.class))).thenAnswer(ignored -> find(destination()));
        when(h.collection(OFFERS).find(eq(h.session), any(Bson.class))).thenAnswer(ignored -> find(CatalogDocuments.offer(MongoCatalogTest.offer(1))));
        when(h.collection(EVENTS).find(eq(h.session), any(Bson.class))).thenAnswer(ignored -> find(CatalogDocuments.event(new CatalogDocuments.Event("event", MongoCatalogTest.offer(1), NOW))));
        for (String collection : List.of(GUILDS, DESTINATIONS, SUBSCRIPTIONS, OFFERS))
            when(h.collection(collection).updateOne(eq(h.session), any(Bson.class), any(Bson.class), any(UpdateOptions.class))).thenReturn(UpdateResult.acknowledged(1, 1L, null));
        when(h.collection(BACKLOG).updateOne(eq(h.session), any(Bson.class), any(Bson.class), argThat((UpdateOptions o) -> SIMPLE.equals(o.getCollation())))).thenReturn(UpdateResult.acknowledged(1, 1L, null));
        when(h.collection(DELIVERIES).replaceOne(eq(h.session), any(Bson.class), any(Document.class), any(ReplaceOptions.class))).thenReturn(UpdateResult.acknowledged(1, 1L, null));
        return h;
    }
    private Optional<AutomaticSendEngine.Decision> authorize(MongoTestSupport h, DeliveryRepository.AuthorizationRequest r) {
        return new MongoDeliveryRepository(h.db, "900").authorize(r, new AutomaticSendEngine(MongoCatalogTest.POLICY));
    }
    @Test void touchesAllGuardsBeforeAtomicAttemptAndSendingCommit() {
        var h = configured(); var result = assertInstanceOf(AutomaticSendEngine.Sending.class, authorize(h, request()).orElseThrow());
        assertEquals(NOW.plusSeconds(20), result.job().attempt().orElseThrow().validUntil());
        var order = inOrder(h.collection(GUILDS), h.collection(DESTINATIONS), h.collection(SUBSCRIPTIONS), h.collection(OFFERS), h.collection(ATTEMPTS), h.collection(DELIVERIES));
        for (String collection : List.of(GUILDS, DESTINATIONS, SUBSCRIPTIONS, OFFERS))
            order.verify(h.collection(collection)).updateOne(eq(h.session), any(Bson.class), any(Bson.class), any(UpdateOptions.class));
        var attempt = ArgumentCaptor.forClass(Document.class); order.verify(h.collection(ATTEMPTS)).insertOne(eq(h.session), attempt.capture());
        var guard = ArgumentCaptor.forClass(Bson.class); order.verify(h.collection(DELIVERIES)).replaceOne(eq(h.session), guard.capture(), any(Document.class), any(ReplaceOptions.class));
        assertEquals("job", attempt.getValue().getString("jobId")); assertEquals("SENDING", attempt.getValue().getString("outcome"));
        assertTrue(bson(guard.getValue()).contains("$$NOW")); assertTrue(bson(guard.getValue()).contains("$lt"));
    }
    @Test void changedRolesBlockRenderingAndCreateNoAttempt() {
        var h = configured();
        when(h.collection(SUBSCRIPTIONS).find(eq(h.session), any(Bson.class))).thenAnswer(ignored -> find(
                new Document(AlertDocuments.subscription(subscription())).append("revision", 2L).append("roleIds", List.of("400"))));
        assertEquals(new AutomaticSendEngine.Blocked(AutomaticSendEngine.BlockReason.REFRESH_PAYLOAD), authorize(h, request()).orElseThrow());
        verifyNoInteractions(h.collection(ATTEMPTS));
    }
    @Test void disabledOrMissingConfigurationSkipsAndReleasesBacklogAtomically() {
        var h = configured(); when(h.collection(GUILDS).find(eq(h.session), any(Bson.class))).thenAnswer(ignored -> find(new Document(guild()).append("enabled", false)));
        assertInstanceOf(AutomaticSendEngine.Skipped.class, authorize(h, request()).orElseThrow());
        verify(h.collection(BACKLOG)).updateOne(eq(h.session), any(Bson.class), any(Bson.class), argThat((UpdateOptions o) -> SIMPLE.equals(o.getCollation())));
        verifyNoInteractions(h.collection(ATTEMPTS));
        var missing = configured(); when(missing.collection(SUBSCRIPTIONS).find(eq(missing.session), any(Bson.class))).thenAnswer(ignored -> find());
        assertInstanceOf(AutomaticSendEngine.Skipped.class, authorize(missing, request()).orElseThrow());
    }
    @Test void expiredOrForeignPermissionEvidenceCannotAuthorize() {
        var h = configured(); var r = request();
        var expired = new DeliveryRepository.AuthorizationRequest(r.jobId(), r.expectedRevision(), r.owner(), r.payload(), new DeliveryRepository.PermissionCheck(DEST, Set.of("300"), NOW), runtimeLease());
        assertEquals(new AutomaticSendEngine.Blocked(AutomaticSendEngine.BlockReason.REPAIR_PERMISSIONS), authorize(h, expired).orElseThrow());
        var wrong = new DeliveryRepository.AuthorizationRequest(r.jobId(), r.expectedRevision(), r.owner(), r.payload(),
                new DeliveryRepository.PermissionCheck(new AlertIdentity.DestinationRef("999", "200", "destination", "incarnation"), Set.of("300"), NOW.plusSeconds(20)), runtimeLease());
        assertInstanceOf(AutomaticSendEngine.Blocked.class, authorize(h, wrong).orElseThrow());
        verifyNoInteractions(h.collection(ATTEMPTS));
    }
    @Test void configurationRaceAbortsBeforeAttemptAndLostFinalFenceNeverReturnsSending() {
        var h = configured(); when(h.collection(SUBSCRIPTIONS).updateOne(eq(h.session), any(Bson.class), any(Bson.class), any(UpdateOptions.class))).thenReturn(UpdateResult.acknowledged(0, 0L, null));
        assertTrue(authorize(h, request()).isEmpty()); verifyNoInteractions(h.collection(ATTEMPTS));
        var lost = configured(); when(lost.collection(DELIVERIES).replaceOne(eq(lost.session), any(Bson.class), any(Document.class), any(ReplaceOptions.class))).thenReturn(UpdateResult.acknowledged(0, 0L, null));
        assertTrue(authorize(lost, request()).isEmpty());
    }
    @Test void attemptFailureOrUnknownCommitPropagatesInsteadOfAllowingTransport() {
        var h = configured(); doThrow(new IllegalStateException("attempt write failed")).when(h.collection(ATTEMPTS)).insertOne(eq(h.session), any(Document.class));
        assertThrows(IllegalStateException.class, () -> authorize(h, request()));
        verify(h.collection(DELIVERIES), never()).replaceOne(eq(h.session), any(Bson.class), any(Document.class), any(ReplaceOptions.class));
    }
    @Test void blockedPreflightCanBeDeferredWithoutConsumingAnAttempt() {
        var h = configured(); var j = job();
        var deferred = new MongoDeliveryRepository(h.db).defer(j.id(), j.revision(), j.lease().orElseThrow(), NOW.plusSeconds(5)).orElseThrow();
        assertEquals(DeliveryJob.State.RETRY_WAIT, deferred.state()); assertEquals(0, deferred.attemptCount());
        verifyNoInteractions(h.collection(ATTEMPTS));
    }
    @Test void missingOwnerOrUnreleasedReviewCannotAuthorize() {
        var h = configured();
        var blocked = new AutomaticSendEngine.Blocked(AutomaticSendEngine.BlockReason.OWNER_RELEASE_REQUIRED);
        assertEquals(blocked, new MongoDeliveryRepository(h.db).authorize(request(), new AutomaticSendEngine(MongoCatalogTest.POLICY)).orElseThrow());
        for (String state : List.of("PENDING", "VERIFIED", "REVOKED")) {
            var review = MongoOwnerReviewTest.released(MongoCatalogTest.offer(1)); review.put("state", state);
            when(h.collection(MongoOwnerReviewSchema.REVIEWS).find(eq(h.session), any(Bson.class))).thenAnswer(ignored -> find(review));
            assertEquals(blocked, authorize(h, request()).orElseThrow());
        }
        when(h.collection(MongoOwnerReviewSchema.REVIEWS).find(eq(h.session), any(Bson.class))).thenAnswer(ignored -> find());
        assertEquals(blocked, authorize(h, request()).orElseThrow());
        verifyNoInteractions(h.collection(ATTEMPTS));
    }
    @Test void ownerReleaseDeadlineCapsTheAttemptAndLostReservationAborts() {
        var h = configured(); var r = MongoOwnerReviewTest.released(MongoCatalogTest.offer(1));
        r.put("releaseUntil", Date.from(NOW.plusSeconds(2)));
        when(h.collection(MongoOwnerReviewSchema.REVIEWS).find(eq(h.session), any(Bson.class))).thenAnswer(ignored -> find(r));
        var sending = assertInstanceOf(AutomaticSendEngine.Sending.class, authorize(h, request()).orElseThrow());
        assertEquals(NOW.plusSeconds(2), sending.job().attempt().orElseThrow().validUntil());
        var lost = configured();
        when(lost.collection(MongoOwnerReviewSchema.REVIEWS).updateOne(eq(lost.session), any(Bson.class), any(Bson.class), any(UpdateOptions.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        assertTrue(authorize(lost, request()).isEmpty()); verifyNoInteractions(lost.collection(ATTEMPTS));
    }
    @Test void exhaustedOrExpiredOrForeignOwnerReleaseBlocks() {
        for (String kind : List.of("exhausted", "expired", "owner", "payload", "template")) {
            var h = configured(); var r = MongoOwnerReviewTest.released(MongoCatalogTest.offer(1));
            if (kind.equals("exhausted")) r.put("usedAttempts", r.getInteger("maximumAttempts"));
            if (kind.equals("expired")) { r.put("releasedAt", Date.from(NOW.minusSeconds(1))); r.put("verifiedAt", Date.from(NOW.minusSeconds(1))); r.put("releaseUntil", Date.from(NOW)); }
            if (kind.equals("owner")) r.put("ownerId", "901");
            if (kind.equals("template")) r.put("templateVersion", "new-template");
            var req = request();
            if (kind.equals("payload")) {
                var p = req.payload();
                req = new DeliveryRepository.AuthorizationRequest(req.jobId(), req.expectedRevision(), req.owner(),
                        new AutomaticSendEngine.PreparedPayload(p.offerKey(), p.subscriptionId(), p.destination(), p.attemptId(), p.nonce(),
                                p.contentRevision(), p.subscriptionRevision(), p.templateVersion(), p.payloadHash(), p.approvedRoles(), "b".repeat(64)),
                        req.permissionCheck(), req.runtimeLease());
            }
            when(h.collection(MongoOwnerReviewSchema.REVIEWS).find(eq(h.session), any(Bson.class))).thenAnswer(ignored -> find(r));
            assertEquals(new AutomaticSendEngine.Blocked(AutomaticSendEngine.BlockReason.OWNER_RELEASE_REQUIRED), authorize(h, req).orElseThrow());
            verifyNoInteractions(h.collection(ATTEMPTS));
        }
    }
}

