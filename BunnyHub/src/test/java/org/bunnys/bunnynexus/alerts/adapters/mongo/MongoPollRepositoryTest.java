package org.bunnys.bunnynexus.alerts.adapters.mongo;

import java.time.*;
import java.util.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.UpdateResult;
import org.bunnys.bunnynexus.alerts.application.*;
import org.bunnys.bunnynexus.alerts.runtime.GamerPowerPoller;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoTestSupport.*;

class MongoPollRepositoryTest {
    final OwnershipRepository.Lease lease = new OwnershipRepository.Lease(GamerPowerPoller.SOURCE, "owner", 3, NOW, NOW.plusSeconds(60));
    @Test void beginFencesOwnershipDueTimePauseAndAttemptWithoutUpsert() {
        var h = new MongoTestSupport(); var c = h.collection(MongoCatalogSchema.SOURCES);
        when(c.updateOne(any(Bson.class), anyList(), any(UpdateOptions.class))).thenReturn(UpdateResult.acknowledged(1, 1L, null));
        assertTrue(new MongoPollRepository(h.db).begin(lease, "$literal-attempt", Duration.ofMinutes(5)));
        var filter = ArgumentCaptor.forClass(Bson.class); var pipeline = ArgumentCaptor.forClass(List.class); var options = ArgumentCaptor.forClass(UpdateOptions.class);
        verify(c).updateOne(filter.capture(), pipeline.capture(), options.capture());
        String json = bson(filter.getValue());
        for (String required : List.of("leaseToken", "generation", "$$NOW", "nextPollAt", "pollPaused", "pollGeneration", "pollFormat")) assertTrue(json.contains(required), required);
        assertFalse(options.getValue().isUpsert()); assertEquals("simple", options.getValue().getCollation().getLocale());
        assertTrue(pipeline.getValue().toString().contains("$literal-attempt")); assertTrue(pipeline.getValue().toString().contains("$literal"));
    }
    @Test void finishMatchesAttemptAndGenerationAndPersistsPauseWithBoundedBackoff() {
        var h = new MongoTestSupport(); var c = h.collection(MongoCatalogSchema.SOURCES);
        when(c.updateOne(any(Bson.class), anyList(), any(UpdateOptions.class))).thenReturn(UpdateResult.acknowledged(0, 0L, null));
        assertFalse(new MongoPollRepository(h.db).finish(lease, "attempt", Duration.ofMinutes(5), new PollRepository.Completion(PollRepository.Outcome.PAUSED, 0, 0)));
        var filter = ArgumentCaptor.forClass(Bson.class); var pipeline = ArgumentCaptor.forClass(List.class);
        verify(c).updateOne(filter.capture(), pipeline.capture(), any(UpdateOptions.class));
        String json = bson(filter.getValue()); assertTrue(json.contains("pollAttempt")); assertTrue(json.contains("pollGeneration")); assertTrue(json.contains("$gt"));
        String updates = pipeline.getValue().toString(); assertTrue(updates.contains("pollPaused=true")); assertTrue(updates.contains("3600000")); assertTrue(updates.contains("$unset"));
    }
    @Test void unknownWritePropagatesAndRuntimeLeaseIsRejected() {
        var h = new MongoTestSupport(); var c = h.collection(MongoCatalogSchema.SOURCES);
        when(c.updateOne(any(Bson.class), anyList(), any(UpdateOptions.class))).thenThrow(new IllegalStateException("unknown"));
        var repository = new MongoPollRepository(h.db);
        assertThrows(IllegalStateException.class, () -> repository.begin(lease, "attempt", Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class, () -> repository.begin(new OwnershipRepository.Lease(new OwnershipRepository.Runtime(), "owner", 1, NOW, NOW.plusSeconds(60)), "attempt", Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class, () -> new PollRepository.Completion(PollRepository.Outcome.SUCCESS, 500, 1));
    }
}
