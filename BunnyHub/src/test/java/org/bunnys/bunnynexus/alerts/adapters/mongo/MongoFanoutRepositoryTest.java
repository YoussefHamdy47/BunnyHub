package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.client.model.*;
import com.mongodb.client.result.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bunnys.bunnynexus.alerts.application.FanoutRepository;
import org.bunnys.bunnynexus.alerts.domain.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoTestSupport.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertSchema.*;

class MongoFanoutRepositoryTest {
    @Test void completingCountedOutboxPlanReleasesItsReservationInSameTransaction() {
        var h = configured(false);
        when(h.collection(PLANS).findOneAndUpdate(eq(h.session), any(Bson.class), anyList(), any(FindOneAndUpdateOptions.class)))
                .thenReturn(checked(AlertDocuments.plan(plan().counted()), NOW));
        when(h.collection(SUBSCRIPTIONS).find(eq(h.session), any(Bson.class))).thenAnswer(ignored -> find());
        assertEquals(FanoutRepository.Result.COMPLETE, process(h));
        var update = ArgumentCaptor.forClass(Bson.class);
        verify(h.collection(BACKLOG)).updateOne(eq(h.session), any(Bson.class), update.capture(), argThat((UpdateOptions o) -> SIMPLE.equals(o.getCollation())));
        assertTrue(bson(update.getValue()).contains("pendingPlans")); assertTrue(bson(update.getValue()).contains("-1"));
        var saved = ArgumentCaptor.forClass(Document.class);
        verify(h.collection(PLANS)).replaceOne(eq(h.session), any(Bson.class), saved.capture(), argThat((ReplaceOptions o) -> SIMPLE.equals(o.getCollation())));
        assertTrue(AlertDocuments.plan(saved.getValue()).outboxCounted());
    }
    @Test void retryingTransactionDerivesSameJobIdentityAndDoesNotAccumulateCounters() {
        var h = configured(false);
        when(h.session.withTransaction(any(), any(com.mongodb.TransactionOptions.class))).thenAnswer(call -> {
            var body = (com.mongodb.client.TransactionBody<?>) call.getArgument(0);
            body.execute(); // Simulate a callback whose transaction is aborted by a retryable database failure.
            return body.execute();
        });
        assertEquals(FanoutRepository.Result.PAGE_COMMITTED, process(h));
        @SuppressWarnings("rawtypes") var inserts = ArgumentCaptor.forClass(List.class);
        verify(h.collection(DELIVERIES), times(2)).insertMany(eq(h.session), inserts.capture());
        assertEquals(((Document) inserts.getAllValues().get(0).getFirst()).getString("_id"),
                ((Document) inserts.getAllValues().get(1).getFirst()).getString("_id"));
        var plans = ArgumentCaptor.forClass(Document.class);
        verify(h.collection(PLANS), times(2)).replaceOne(eq(h.session), any(Bson.class), plans.capture(), argThat((ReplaceOptions o) -> SIMPLE.equals(o.getCollation())));
        for (Document p : plans.getAllValues()) assertEquals(1, AlertDocuments.plan(p).inserted());
    }
    @Test void unsupportedSubscriptionSchemaBlocksPageWithoutAdvancingCursor() {
        var h = configured(false);
        when(h.collection(SUBSCRIPTIONS).find(eq(h.session), any(Bson.class))).thenAnswer(ignored ->
                find(new Document(AlertDocuments.subscription(subscription())).append("schemaVersion", 2)));
        assertThrows(IllegalArgumentException.class, () -> process(h));
        verify(h.collection(DELIVERIES), never()).insertMany(any(), anyList());
        verify(h.collection(PLANS), never()).replaceOne(any(), any(Bson.class), any(Document.class), argThat((ReplaceOptions o) -> SIMPLE.equals(o.getCollation())));
    }
    private MongoTestSupport configured(boolean duplicate) {
        var h = new MongoTestSupport();
        when(h.collection(PLANS).findOneAndUpdate(eq(h.session), any(Bson.class), anyList(), any(FindOneAndUpdateOptions.class)))
                .thenReturn(checked(AlertDocuments.plan(plan()), NOW));
        when(h.collection(SUBSCRIPTIONS).find(eq(h.session), any(Bson.class))).thenAnswer(ignored -> find(AlertDocuments.subscription(subscription())));
        when(h.collection(GUILDS).find(eq(h.session), any(Bson.class))).thenAnswer(ignored -> find(guild()));
        when(h.collection(DESTINATIONS).find(eq(h.session), any(Bson.class))).thenAnswer(ignored -> find(destination()));
        when(h.collection(DELIVERIES).find(eq(h.session), any(Bson.class))).thenAnswer(ignored -> duplicate ? find(new Document("_id", "existing").append("channelId", "200")) : find());
        when(h.collection(BACKLOG).updateOne(eq(h.session), any(Bson.class), any(Bson.class), argThat((UpdateOptions o) -> SIMPLE.equals(o.getCollation())))).thenReturn(UpdateResult.acknowledged(1, 1L, null));
        when(h.collection(PLANS).replaceOne(eq(h.session), any(Bson.class), any(Document.class), argThat((ReplaceOptions o) -> SIMPLE.equals(o.getCollation())))).thenReturn(UpdateResult.acknowledged(1, 1L, null));
        return h;
    }
    private FanoutRepository.Result process(MongoTestSupport h) {
        return new MongoFanoutRepository(h.db).processPage("plan", plan().revision(), plan().lease().orElseThrow(), 100);
    }
    @Test void commitsJobsBeforeCursorWithSameSessionAndFreshLeaseFence() {
        var h = configured(false); assertEquals(FanoutRepository.Result.PAGE_COMMITTED, process(h));
        var order = inOrder(h.collection(BACKLOG), h.collection(DELIVERIES), h.collection(PLANS));
        order.verify(h.collection(PLANS)).findOneAndUpdate(eq(h.session), any(Bson.class), anyList(), any(FindOneAndUpdateOptions.class));
        order.verify(h.collection(DELIVERIES)).find(eq(h.session), any(Bson.class));
        order.verify(h.collection(BACKLOG)).updateOne(eq(h.session), any(Bson.class), any(Bson.class), argThat((UpdateOptions o) -> SIMPLE.equals(o.getCollation())));
        order.verify(h.collection(DELIVERIES)).insertMany(eq(h.session), anyList());
        var guard = ArgumentCaptor.forClass(Bson.class); var saved = ArgumentCaptor.forClass(Document.class);
        order.verify(h.collection(PLANS)).replaceOne(eq(h.session), guard.capture(), saved.capture(), argThat((ReplaceOptions o) -> SIMPLE.equals(o.getCollation())));
        String filter = bson(guard.getValue());
        for (String field : List.of("revision", "leaseToken", "generation", "$$NOW", "leaseUntil")) assertTrue(filter.contains(field));
        FanoutPlan next = AlertDocuments.plan(saved.getValue());
        assertEquals(1, next.inserted()); assertEquals("sub", next.afterSubscriptionId().orElseThrow());
        verify(h.session).close();
    }
    @Test void replayPreservesExistingJobAndCountsDuplicateWithoutCapacityReservation() {
        var h = configured(true); assertEquals(FanoutRepository.Result.PAGE_COMMITTED, process(h));
        verify(h.collection(DELIVERIES), never()).insertMany(any(), anyList());
        verify(h.collection(DELIVERIES), never()).replaceOne(any(), any(Bson.class), any(Document.class), argThat((ReplaceOptions o) -> SIMPLE.equals(o.getCollation())));
        verifyNoInteractions(h.collection(BACKLOG));
        var saved = ArgumentCaptor.forClass(Document.class);
        verify(h.collection(PLANS)).replaceOne(eq(h.session), any(Bson.class), saved.capture(), argThat((ReplaceOptions o) -> SIMPLE.equals(o.getCollation())));
        assertEquals(1, AlertDocuments.plan(saved.getValue()).duplicates());
    }
    @Test void saturatedBacklogLeavesJobsAndCursorUntouched() {
        var h = configured(false);
        when(h.collection(BACKLOG).updateOne(eq(h.session), any(Bson.class), any(Bson.class), argThat((UpdateOptions o) -> SIMPLE.equals(o.getCollation())))).thenReturn(UpdateResult.acknowledged(0, 0L, null));
        assertEquals(FanoutRepository.Result.BACKPRESSURE, process(h));
        verify(h.collection(DELIVERIES), never()).insertMany(any(), anyList());
        verify(h.collection(PLANS), never()).replaceOne(any(), any(Bson.class), any(Document.class), argThat((ReplaceOptions o) -> SIMPLE.equals(o.getCollation())));
    }
    @Test void lostFenceAfterInsertAbortsCallbackAndNeverReportsCommit() {
        var h = configured(false);
        when(h.collection(PLANS).replaceOne(eq(h.session), any(Bson.class), any(Document.class), argThat((ReplaceOptions o) -> SIMPLE.equals(o.getCollation())))).thenReturn(UpdateResult.acknowledged(0, 0L, null));
        assertEquals(FanoutRepository.Result.CONFLICT, process(h));
        verify(h.collection(DELIVERIES)).insertMany(eq(h.session), anyList());
        // The callback throws through withTransaction, which is responsible for rollback in the real driver.
        verify(h.session).close();
    }
    @Test void databaseFailureCannotAdvanceCursorOrBecomeSuccess() {
        var h = configured(false);
        doThrow(new IllegalStateException("injected database failure")).when(h.collection(DELIVERIES)).insertMany(eq(h.session), anyList());
        assertThrows(IllegalStateException.class, () -> process(h));
        verify(h.collection(PLANS), never()).replaceOne(any(), any(Bson.class), any(Document.class), argThat((ReplaceOptions o) -> SIMPLE.equals(o.getCollation())));
        verify(h.session).close();
    }
    @Test void finalEmptyPageCompletesAndOldOwnerCannotScan() {
        var h = configured(false);
        when(h.collection(SUBSCRIPTIONS).find(eq(h.session), any(Bson.class))).thenAnswer(ignored -> find());
        assertEquals(FanoutRepository.Result.COMPLETE, process(h));
        verifyNoInteractions(h.collection(BACKLOG));
        var stale = new MongoTestSupport();
        assertEquals(FanoutRepository.Result.CONFLICT, process(stale));
        verifyNoInteractions(stale.collection(SUBSCRIPTIONS));
    }
    @Test void claimUsesServerClockLiteralTokenAndGenerationWithoutConstructingWorkers() {
        var h = new MongoTestSupport(); var repository = new MongoFanoutRepository(h.db);
        verifyNoInteractions(h.collection(PLANS));
        repository.claim("plan", "$untrusted-token", Duration.ofSeconds(30));
        @SuppressWarnings("rawtypes") var updates = ArgumentCaptor.forClass(List.class);
        verify(h.collection(PLANS)).findOneAndUpdate(any(Bson.class), updates.capture(), argThat((FindOneAndUpdateOptions o) -> SIMPLE.equals(o.getCollation())));
        String update = updates.getValue().toString();
        assertTrue(update.contains("$literal=$untrusted-token")); assertTrue(update.contains("$$NOW")); assertTrue(update.contains("generation"));
        assertThrows(IllegalArgumentException.class, () -> repository.processPage("plan", 2, plan().lease().orElseThrow(), 501));
    }
}
