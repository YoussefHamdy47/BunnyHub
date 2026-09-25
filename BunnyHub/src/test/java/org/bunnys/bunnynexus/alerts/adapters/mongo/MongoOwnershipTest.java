package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.client.model.*;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bunnys.bunnynexus.alerts.application.OwnershipRepository;
import org.bunnys.bunnynexus.alerts.domain.AlertIdentity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Duration;
import java.util.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MongoOwnershipTest {
    private Document grant(OwnershipRepository.Resource resource) {
        return new Document("_id", MongoOwnershipRepository.id(resource)).append("schemaVersion", 1).append("revision", 2L)
                .append("generation", 3L).append("leaseToken", "owner").append("checkedAt", Date.from(NOW)).append("leaseUntil", Date.from(NOW.plusSeconds(30)));
    }
    @Test void acquisitionUsesDatabaseExpiryAndNeverUpsertsOrResetsGenerations() {
        var h = new MongoTestSupport(); var resource = new OwnershipRepository.Runtime();
        when(h.collection(MongoRuntimeSchema.OWNERSHIP).findOneAndUpdate(any(Bson.class), anyList(), any(FindOneAndUpdateOptions.class)))
                .thenReturn(grant(resource));
        var lease = new MongoOwnershipRepository(h.db).acquire(resource, "owner", Duration.ofSeconds(30)).orElseThrow();
        assertEquals(3, lease.generation());
        var filter = ArgumentCaptor.forClass(Bson.class); var options = ArgumentCaptor.forClass(FindOneAndUpdateOptions.class);
        verify(h.collection(MongoRuntimeSchema.OWNERSHIP)).findOneAndUpdate(filter.capture(), anyList(), options.capture());
        assertTrue(bson(filter.getValue()).contains("$$NOW")); assertTrue(bson(filter.getValue()).contains("$lte"));
        assertFalse(options.getValue().isUpsert()); assertEquals("simple", options.getValue().getCollation().getLocale());
    }
    @Test void renewalAndReleaseFenceTokenGenerationAndExclusiveExpiry() {
        var h = new MongoTestSupport(); var resource = new OwnershipRepository.Runtime();
        var lease = new OwnershipRepository.Lease(resource, "old", 2, NOW, NOW.plusSeconds(30));
        when(h.collection(MongoRuntimeSchema.OWNERSHIP).findOneAndUpdate(any(Bson.class), anyList(), any(FindOneAndUpdateOptions.class))).thenReturn(null);
        when(h.collection(MongoRuntimeSchema.OWNERSHIP).updateOne(any(Bson.class), anyList(), any(UpdateOptions.class))).thenReturn(UpdateResult.acknowledged(0, 0L, null));
        var repository = new MongoOwnershipRepository(h.db);
        assertTrue(repository.renew(lease, Duration.ofSeconds(30)).isEmpty()); assertFalse(repository.release(lease));
        var filter = ArgumentCaptor.forClass(Bson.class);
        verify(h.collection(MongoRuntimeSchema.OWNERSHIP)).updateOne(filter.capture(), anyList(), any(UpdateOptions.class));
        String json = bson(filter.getValue()); assertTrue(json.contains("old")); assertTrue(json.contains("generation"));
        assertTrue(json.contains("$gt")); assertTrue(json.contains("$$NOW"));
    }
    @Test void sourceOwnershipUsesTheExistingObservationGuardContract() {
        var h = new MongoTestSupport(); var source = new OwnershipRepository.Source(new AlertIdentity.SourceId("gamerpower"), "EG");
        when(h.collection(MongoCatalogSchema.SOURCES).findOneAndUpdate(any(Bson.class), anyList(), any(FindOneAndUpdateOptions.class)))
                .thenReturn(grant(source));
        var lease = new MongoOwnershipRepository(h.db).acquire(source, "owner", Duration.ofSeconds(30)).orElseThrow();
        assertEquals(source.sourceId(), lease.sourceLease().source()); assertEquals("EG", lease.sourceLease().scope());
        var filter = ArgumentCaptor.forClass(Bson.class);
        verify(h.collection(MongoCatalogSchema.SOURCES)).findOneAndUpdate(filter.capture(), anyList(), any(FindOneAndUpdateOptions.class));
        assertTrue(bson(filter.getValue()).contains("sourceId")); assertTrue(bson(filter.getValue()).contains("gamerpower"));
    }
    @Test void unknownAcquisitionIsNotPermissionAndDurationsAreBounded() {
        var h = new MongoTestSupport(); var error = new IllegalStateException("unknown result");
        when(h.collection(MongoRuntimeSchema.OWNERSHIP).findOneAndUpdate(any(Bson.class), anyList(), any(FindOneAndUpdateOptions.class))).thenThrow(error);
        assertSame(error, assertThrows(IllegalStateException.class, () -> new MongoOwnershipRepository(h.db).acquire(new OwnershipRepository.Runtime(), "owner", Duration.ofSeconds(30))));
        assertThrows(IllegalArgumentException.class, () -> OwnershipRepository.durationMillis(Duration.ofMinutes(16)));
        assertThrows(IllegalArgumentException.class, () -> OwnershipRepository.durationMillis(Duration.ofNanos(1)));
    }
}
