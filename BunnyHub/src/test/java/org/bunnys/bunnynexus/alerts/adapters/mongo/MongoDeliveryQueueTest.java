package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.client.model.*;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bunnys.bunnynexus.alerts.domain.DeliveryJob;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoTestSupport.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertSchema.*;

class MongoDeliveryQueueTest {
    @Test void claimsUseExactIdentityComparisons() {
        var h = new MongoTestSupport();
        new MongoDeliveryQueue(h.db).claim("Job", "Token", java.time.Duration.ofSeconds(30));
        verify(h.collection(DELIVERIES)).findOneAndUpdate(any(Bson.class), anyList(),
                argThat((FindOneAndUpdateOptions o) -> SIMPLE.equals(o.getCollation())));
    }
    private MongoTestSupport configured(boolean expired) {
        var h = new MongoTestSupport();
        when(h.collection(DELIVERIES).findOneAndUpdate(eq(h.session), any(Bson.class), anyList(), any(FindOneAndUpdateOptions.class)))
                .thenReturn(checked(AlertDocuments.delivery(sending()), expired ? NOW.plusSeconds(60) : NOW));
        when(h.collection(ATTEMPTS).updateOne(eq(h.session), any(Bson.class), any(Bson.class), argThat((UpdateOptions o) -> SIMPLE.equals(o.getCollation())))).thenReturn(UpdateResult.acknowledged(1, 1L, null));
        when(h.collection(BACKLOG).updateOne(eq(h.session), any(Bson.class), any(Bson.class), argThat((UpdateOptions o) -> SIMPLE.equals(o.getCollation())))).thenReturn(UpdateResult.acknowledged(1, 1L, null));
        when(h.collection(DELIVERIES).replaceOne(eq(h.session), any(Bson.class), any(Document.class), argThat((ReplaceOptions o) -> SIMPLE.equals(o.getCollation())))).thenReturn(UpdateResult.acknowledged(1, 1L, null));
        return h;
    }
    @Test void acceptedReceiptAndTerminalBacklogChangeShareTransaction() {
        var h = configured(false); var job = sending();
        var result = new MongoDeliveryQueue(h.db).recordOutcome(job.id(), job.revision(), job.lease().orElseThrow(),
                "attempt", new DeliveryJob.Accepted("500"), Optional.empty()).orElseThrow();
        assertEquals(DeliveryJob.State.SENT, result.state()); assertEquals("500", result.messageId().orElseThrow());
        verify(h.collection(ATTEMPTS)).updateOne(eq(h.session), any(Bson.class), any(Bson.class), argThat((UpdateOptions o) -> SIMPLE.equals(o.getCollation())));
        verify(h.collection(BACKLOG)).updateOne(eq(h.session), any(Bson.class), any(Bson.class), argThat((UpdateOptions o) -> SIMPLE.equals(o.getCollation())));
        var fence = ArgumentCaptor.forClass(Bson.class);
        verify(h.collection(DELIVERIES)).replaceOne(eq(h.session), fence.capture(), any(Document.class), argThat((ReplaceOptions o) -> SIMPLE.equals(o.getCollation())));
        for (String field : List.of("attempt._id", "generation", "revision", "leaseToken", "$$NOW")) assertTrue(bson(fence.getValue()).contains(field));
    }
    @Test void sendingRecoveryRecordsUncertaintyWithoutFreeingDurableCapacity() {
        var h = configured(true);
        var result = new MongoDeliveryQueue(h.db).recoverExpired("job", sending().revision()).orElseThrow();
        assertEquals(DeliveryJob.State.UNCERTAIN, result.state());
        verify(h.collection(ATTEMPTS)).updateOne(eq(h.session), any(Bson.class), any(Bson.class), argThat((UpdateOptions o) -> SIMPLE.equals(o.getCollation())));
        verifyNoInteractions(h.collection(BACKLOG));
    }
    @Test void recoveryBeforeAuthorizationDoesNotInventAttempt() {
        var h = configured(true); var leased = DeliveryJob.restore(sending().snapshot());
        var ready = DeliveryJob.ready("job", leased.key(), leased.destination(), "sub", NOW).claim("w", NOW, NOW.plusSeconds(30));
        when(h.collection(DELIVERIES).findOneAndUpdate(eq(h.session), any(Bson.class), anyList(), any(FindOneAndUpdateOptions.class)))
                .thenReturn(checked(AlertDocuments.delivery(ready), NOW.plusSeconds(30)));
        assertEquals(DeliveryJob.State.READY, new MongoDeliveryQueue(h.db).recoverExpired("job", ready.revision()).orElseThrow().state());
        verifyNoInteractions(h.collection(ATTEMPTS), h.collection(BACKLOG));
    }
    @Test void missingAttemptOrBadBacklogAbortsInsteadOfManufacturingReceipt() {
        var h = configured(false); var job = sending();
        when(h.collection(ATTEMPTS).updateOne(eq(h.session), any(Bson.class), any(Bson.class), argThat((UpdateOptions o) -> SIMPLE.equals(o.getCollation())))).thenReturn(UpdateResult.acknowledged(0, 0L, null));
        assertThrows(IllegalStateException.class, () -> new MongoDeliveryQueue(h.db).recordOutcome("job", job.revision(),
                job.lease().orElseThrow(), "attempt", new DeliveryJob.Accepted("500"), Optional.empty()));
        verify(h.collection(DELIVERIES), never()).replaceOne(any(), any(Bson.class), any(Document.class), argThat((ReplaceOptions o) -> SIMPLE.equals(o.getCollation())));
    }
    @Test void conflictAndUnknownCommitAreDistinctOutcomes() {
        var h = new MongoTestSupport(); var job = sending();
        assertTrue(new MongoDeliveryQueue(h.db).recoverExpired("job", job.revision()).isEmpty());
        when(h.session.withTransaction(any(), any(com.mongodb.TransactionOptions.class)))
                .thenThrow(new IllegalStateException("unresolved commit"));
        assertThrows(IllegalStateException.class, () -> new MongoDeliveryQueue(h.db).recoverExpired("job", job.revision()));
    }
    @Test void dueQueriesAreBoundedByGuildAndExplicitQueueState() {
        var h = new MongoTestSupport();
        var found = find(); when(h.collection(DELIVERIES).find(any(Bson.class))).thenReturn(found);
        assertTrue(new MongoDeliveryQueue(h.db).due(DeliveryJob.State.READY, "100", 8).isEmpty());
        verify(found).limit(8); verify(found).batchSize(8);
        var query = ArgumentCaptor.forClass(Bson.class); verify(h.collection(DELIVERIES)).find(query.capture());
        assertTrue(bson(query.getValue()).contains("guildId")); assertFalse(bson(query.getValue()).contains("$or"));
        assertThrows(IllegalArgumentException.class, () -> new MongoDeliveryQueue(h.db).due(DeliveryJob.State.SENDING, "100", 8));
        assertThrows(IllegalArgumentException.class, () -> new MongoDeliveryQueue(h.db).due(DeliveryJob.State.READY, "100", 501));
    }
}
