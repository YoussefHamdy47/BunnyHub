package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.client.model.*;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bunnys.bunnynexus.alerts.application.ObservationRepository;
import org.bunnys.bunnynexus.alerts.domain.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoTestSupport.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoCatalogSchema.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertSchema.*;

class MongoCatalogTest {
    @Test void metadataUpdatePreservesExistingEventPlanAndCapacity() {
        var h = configured(); var old = offer(1); var original = command();
        when(h.collection(OFFERS).find(eq(h.session), any(Bson.class))).thenAnswer(ignored -> find(CatalogDocuments.offer(old)));
        when(h.collection(EVENTS).find(eq(h.session), any(Bson.class))).thenAnswer(ignored -> find(CatalogDocuments.event(new CatalogDocuments.Event("original-event", old, NOW))));
        when(h.collection(PLANS).find(eq(h.session), any(Bson.class))).thenAnswer(ignored -> find(new Document("_id", "existing-plan")));
        when(h.collection(OFFERS).replaceOne(eq(h.session), any(Bson.class), any(Document.class), any(ReplaceOptions.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        var update = new ObservationRepository.Commit("unused-new-event", "unused-new-plan", offer(2), NOW, original.sourceLease(), 1, 11);
        assertEquals(ObservationRepository.Result.OBSERVATION_UPDATED, new MongoObservationRepository(h.db).commit(update, POLICY));
        verify(h.collection(EVENTS), never()).insertOne(any(), any(Document.class));
        verify(h.collection(PLANS), never()).insertOne(any(), any(Document.class));
        verifyNoInteractions(h.collection(BACKLOG));
    }
    static Offer offer(long revision) {
        return new Offer(KEY, "edition", Offer.Kind.FREE_TO_KEEP_BASE_GAME,
                new Offer.Evidence(Offer.Proof.VERIFIED, Offer.Proof.VERIFIED, Offer.Proof.VERIFIED, Offer.Proof.VERIFIED,
                        new AlertIdentity.SourceId("source"), "item", "rule-v1"), Optional.of(new Offer.Money(0, Currency.getInstance("EGP"))),
                Optional.of(NOW.minusSeconds(10)), Optional.of(NOW.plusSeconds(600)), NOW, revision);
    }
    static final FreeGamePolicy POLICY = new FreeGamePolicy(Duration.ofMinutes(5), false);
    private ObservationRepository.Commit command() {
        return new ObservationRepository.Commit("event", "plan", offer(1), NOW,
                new ObservationRepository.SourceLease(new AlertIdentity.SourceId("source"), "EG", "owner", 1), 0, 10);
    }
    private MongoTestSupport configured() {
        var h = new MongoTestSupport();
        when(h.collection(SOURCES).findOneAndUpdate(eq(h.session), any(Bson.class), anyList(), any(FindOneAndUpdateOptions.class)))
                .thenReturn(new Document("checkedAt", Date.from(NOW)));
        for (String collection : List.of(MAPPINGS, OFFERS, EVENTS, PLANS))
            when(h.collection(collection).find(eq(h.session), any(Bson.class))).thenAnswer(ignored -> find());
        when(h.collection(BACKLOG).updateOne(eq(h.session), any(Bson.class), any(Bson.class), argThat((UpdateOptions o) -> SIMPLE.equals(o.getCollation())))).thenReturn(UpdateResult.acknowledged(1, 1L, null));
        when(h.collection(SOURCES).updateOne(eq(h.session), any(Bson.class), any(Bson.class), argThat((UpdateOptions o) -> SIMPLE.equals(o.getCollation())))).thenReturn(UpdateResult.acknowledged(1, 1L, null));
        return h;
    }
    @Test void catalogAndImmutableEventRoundTripRejectsMismatchedIdentityAndVersions() {
        var o = offer(1); assertEquals(o, CatalogDocuments.offer(Document.parse(CatalogDocuments.offer(o).toJson())));
        var event = new CatalogDocuments.Event("event", o, NOW);
        assertEquals(event, CatalogDocuments.event(Document.parse(CatalogDocuments.event(event).toJson())));
        assertThrows(IllegalArgumentException.class, () -> CatalogDocuments.offer(new Document(CatalogDocuments.offer(o)).append("_id", "wrong")));
        assertThrows(IllegalArgumentException.class, () -> CatalogDocuments.event(new Document(CatalogDocuments.event(event)).append("payloadVersion", 2)));
        assertThrows(IllegalArgumentException.class, () -> CatalogDocuments.event(new Document(CatalogDocuments.event(event)).append("offerId", "wrong")));
    }
    @Test void commitsOfferEventAndCountedPlanInOneSessionWithSourceFenceLast() {
        var h = configured();
        assertEquals(ObservationRepository.Result.EVENT_CREATED, new MongoObservationRepository(h.db).commit(command(), POLICY));
        var order = inOrder(h.collection(OFFERS), h.collection(EVENTS), h.collection(PLANS), h.collection(SOURCES));
        order.verify(h.collection(OFFERS)).insertOne(eq(h.session), any(Document.class));
        order.verify(h.collection(EVENTS)).insertOne(eq(h.session), any(Document.class));
        var plan = ArgumentCaptor.forClass(Document.class); order.verify(h.collection(PLANS)).insertOne(eq(h.session), plan.capture());
        order.verify(h.collection(SOURCES)).updateOne(eq(h.session), any(Bson.class), any(Bson.class), argThat((UpdateOptions o) -> SIMPLE.equals(o.getCollation())));
        assertTrue(AlertDocuments.plan(plan.getValue()).outboxCounted());
        verify(h.session).close();
    }
    @Test void outboxSaturationWritesNeitherObservationNorEvent() {
        var h = configured();
        when(h.collection(BACKLOG).updateOne(eq(h.session), any(Bson.class), any(Bson.class), argThat((UpdateOptions o) -> SIMPLE.equals(o.getCollation())))).thenReturn(UpdateResult.acknowledged(0, 0L, null));
        assertEquals(ObservationRepository.Result.BACKPRESSURE, new MongoObservationRepository(h.db).commit(command(), POLICY));
        for (String collection : List.of(OFFERS, EVENTS, PLANS, MAPPINGS)) verify(h.collection(collection), never()).insertOne(any(), any(Document.class));
    }
    @Test void replayedSourceOrderCannotUpdateOfferOrCreateAnotherEvent() {
        var h = configured();
        when(h.collection(MAPPINGS).find(eq(h.session), any(Bson.class))).thenAnswer(ignored -> find(
                new Document("schemaVersion", 1).append("sourceOrder", 10L)));
        assertEquals(ObservationRepository.Result.STALE_OBSERVATION, new MongoObservationRepository(h.db).commit(command(), POLICY));
        verify(h.collection(OFFERS), never()).insertOne(any(), any(Document.class));
        verifyNoInteractions(h.collection(BACKLOG));
    }
    @Test void staleCanonicalRevisionIsRejectedBeforeAnyReservation() {
        var h = configured();
        when(h.collection(OFFERS).find(eq(h.session), any(Bson.class))).thenAnswer(ignored -> find(CatalogDocuments.offer(offer(2))));
        assertEquals(ObservationRepository.Result.STALE_OBSERVATION, new MongoObservationRepository(h.db).commit(command(), POLICY));
        verifyNoInteractions(h.collection(BACKLOG));
    }
    @Test void existingManualEventIsReusedWithItsOriginalObservationCutoff() {
        var h = configured(); var oldTime = NOW.minusSeconds(2); var o = offer(1);
        var older = new Offer(o.key(), o.editionId(), o.kind(), o.evidence(), o.price(), o.startsAt(), o.endsAt(), oldTime, 1);
        when(h.collection(EVENTS).find(eq(h.session), any(Bson.class))).thenAnswer(ignored -> find(CatalogDocuments.event(new CatalogDocuments.Event("manual-event", older, oldTime))));
        assertEquals(ObservationRepository.Result.OBSERVATION_UPDATED, new MongoObservationRepository(h.db).commit(command(), POLICY));
        verify(h.collection(EVENTS), never()).insertOne(any(), any(Document.class));
        var plan = ArgumentCaptor.forClass(Document.class); verify(h.collection(PLANS)).insertOne(eq(h.session), plan.capture());
        assertEquals("manual-event", AlertDocuments.plan(plan.getValue()).eventId());
        assertEquals(oldTime, AlertDocuments.plan(plan.getValue()).observedAt());
    }
    @Test void lostSourceLeaseOrFailedPlanInsertCannotBeReportedAsCommitted() {
        var h = configured();
        when(h.collection(SOURCES).updateOne(eq(h.session), any(Bson.class), any(Bson.class), argThat((UpdateOptions o) -> SIMPLE.equals(o.getCollation())))).thenReturn(UpdateResult.acknowledged(0, 0L, null));
        assertEquals(ObservationRepository.Result.STALE_OBSERVATION, new MongoObservationRepository(h.db).commit(command(), POLICY));
        var broken = configured();
        doThrow(new IllegalStateException("injected write failure")).when(broken.collection(PLANS)).insertOne(eq(broken.session), any(Document.class));
        assertThrows(IllegalStateException.class, () -> new MongoObservationRepository(broken.db).commit(command(), POLICY));
    }
    @Test void unknownEligibilityUpdatesCatalogButCreatesNoAnnouncement() {
        var h = configured(); var o = offer(1);
        var unknown = new Offer(o.key(), o.editionId(), Offer.Kind.UNKNOWN, o.evidence(), o.price(), o.startsAt(), o.endsAt(), NOW, 1);
        var c = command();
        assertEquals(ObservationRepository.Result.OBSERVATION_UPDATED, new MongoObservationRepository(h.db).commit(
                new ObservationRepository.Commit(c.eventId(), c.automaticPlanId(), unknown, NOW, c.sourceLease(), 0, 10), POLICY));
        verify(h.collection(EVENTS), never()).insertOne(any(), any(Document.class));
        verify(h.collection(PLANS), never()).insertOne(any(), any(Document.class));
    }
    @Test void catalogMigrationIsAdditiveAndProtectsCanonicalUniqueness() {
        assertNotEquals(MongoAlertSchema.MIGRATION, MongoCatalogSchema.MIGRATION);
        assertTrue(MongoCatalogSchema.checksum().matches("[a-f0-9]{64}"));
        assertTrue(MongoCatalogSchema.indexes().stream().allMatch(MongoAlertSchema.IndexSpec::unique));
        assertFalse(MongoAlertSchema.indexes().stream().anyMatch(spec -> spec.collection().equals(OFFERS)));
    }
}
