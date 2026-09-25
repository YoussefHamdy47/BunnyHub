package org.bunnys.bunnynexus.alerts.adapters.mongo;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bunnys.bunnynexus.alerts.application.SchedulingRepository;
import org.bunnys.bunnynexus.alerts.domain.DeliveryJob;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoTestSupport.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertSchema.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MongoSchedulingTest {
    @Test void duePagesUseStableKeysetDatabaseTimeAndFiniteLimits() {
        var h = new MongoTestSupport(); var result = find();
        when(h.collection(DELIVERIES).find(any(Bson.class))).thenReturn(result);
        new MongoSchedulingRepository(h.db).due(DeliveryJob.State.READY, Optional.of("100"), Optional.of(new SchedulingRepository.Cursor(NOW, "job")), 5);
        var filter = ArgumentCaptor.forClass(Bson.class); verify(h.collection(DELIVERIES)).find(filter.capture());
        var json = bson(filter.getValue()); assertTrue(json.contains("dueAt")); assertTrue(json.contains("$gt"));
        assertTrue(json.contains("guildId")); assertTrue(json.contains("$$NOW"));
        verify(result).limit(5); verify(result).batchSize(5); verify(result).collation(SIMPLE);
    }
    @Test void separateRecoveryStatesAndDisabledGuildsRemainVisible() {
        var h = new MongoTestSupport();
        when(h.collection(DELIVERIES).find(any(Bson.class))).thenAnswer(call -> find());
        when(h.collection(GUILDS).find(any(Bson.class))).thenAnswer(call -> find(new Document("_id", "100")));
        var repository = new MongoSchedulingRepository(h.db);
        assertEquals(List.of("100"), repository.guilds(Optional.empty(), 5));
        var filter = ArgumentCaptor.forClass(Bson.class); verify(h.collection(GUILDS)).find(filter.capture());
        assertFalse(bson(filter.getValue()).contains("enabled"));
        repository.expired(DeliveryJob.State.SENDING, 5);
        assertThrows(IllegalArgumentException.class, () -> repository.expired(DeliveryJob.State.READY, 5));
        assertThrows(IllegalArgumentException.class, () -> repository.due(DeliveryJob.State.SENDING, Optional.empty(), Optional.empty(), 5));
    }
    @Test void oversizedDriverPageFailsRatherThanGrowingMemoryUnboundedly() {
        var h = new MongoTestSupport();
        when(h.collection(GUILDS).find(any(Bson.class))).thenAnswer(call -> find(new Document("_id", "100"), new Document("_id", "101")));
        assertThrows(IllegalStateException.class, () -> new MongoSchedulingRepository(h.db).guilds(Optional.empty(), 1));
    }
}
