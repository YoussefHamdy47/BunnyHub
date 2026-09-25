package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.client.TransactionBody;
import com.mongodb.TransactionOptions;
import com.mongodb.client.model.*;
import com.mongodb.client.result.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bunnys.bunnynexus.alerts.application.*;
import org.bunnys.bunnynexus.alerts.domain.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoTestSupport.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoAlertSchema.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoConfigurationSchema.*;
import static org.bunnys.bunnynexus.alerts.application.ConfigurationRepository.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MongoConfigurationRepositoryTest {
    private final ConfigurationPolicy policy = new ConfigurationPolicy(new ConfigurationPolicy.Limits(2, 4, 2,
            Set.of(KEY.store()), Set.of(KEY.market()), Set.of(Topic.FREE_GAME)));
    private final ConfigurationChange create = new ConfigurationChange("400", "300", "100", 0,
            new ConfigurationChange.PutSubscription("200", KEY.store(), KEY.market(), Topic.FREE_GAME, true, Set.of("500")));
    private GuildConfiguration initial() { return policy.apply(GuildConfiguration.absent("100"), create, NOW); }
    private ConfigurationAccess.Proof proof(ConfigurationChange change, GuildConfiguration before) {
        return new ConfigurationAccess.Proof(ConfigurationAccess.requirements(change, policy.apply(before, change, NOW)), NOW, NOW.plusSeconds(10));
    }
    private MongoTestSupport fixture(GuildConfiguration before) {
        var h = new MongoTestSupport();
        when(h.collection(AUDIT).find(eq(h.session), any(Bson.class))).thenAnswer(call -> find());
        when(h.collection(CONFIGURATIONS).find(eq(h.session), any(Bson.class))).thenAnswer(call -> before.revision() == 0 ? find() : find(ConfigurationDocuments.configuration(before)));
        when(h.collection(GUILDS).findOneAndUpdate(eq(h.session), any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(checked(new Document("_id", "100").append("schemaVersion", 1).append("configFormat", 1)
                        .append("revision", before.revision()).append("enabled", before.enabled()), NOW));
        for (String name : List.of(GUILDS, DESTINATIONS, SUBSCRIPTIONS)) {
            when(h.collection(name).updateOne(eq(h.session), any(Bson.class), any(Bson.class), any(UpdateOptions.class)))
                    .thenReturn(UpdateResult.acknowledged(1, 1L, null));
            when(h.collection(name).deleteOne(eq(h.session), any(Bson.class), any(DeleteOptions.class))).thenReturn(DeleteResult.acknowledged(1));
        }
        when(h.collection(CONFIGURATIONS).replaceOne(eq(h.session), any(Bson.class), any(Document.class), any(ReplaceOptions.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        return h;
    }
    @Test void creationAtomicallyWritesCompatibleGuardsAggregateAndAuditWithFinalTimeFence() {
        var absent = GuildConfiguration.absent("100"); var h = fixture(absent);
        assertEquals(new Result(Status.APPLIED, 1), new MongoConfigurationRepository(h.db).commit(create, proof(create, absent), policy));
        var sub = ArgumentCaptor.forClass(Document.class); var dest = ArgumentCaptor.forClass(Document.class);
        verify(h.collection(SUBSCRIPTIONS)).insertOne(eq(h.session), sub.capture());
        verify(h.collection(DESTINATIONS)).insertOne(eq(h.session), dest.capture());
        var projected = AlertDocuments.subscription(sub.getValue(), guild(), dest.getValue());
        assertEquals(Set.of("500"), projected.roleIds()); assertTrue(projected.destinationEnabled());
        var audit = ArgumentCaptor.forClass(Document.class); verify(h.collection(AUDIT)).insertOne(eq(h.session), audit.capture());
        assertTrue(ConfigurationDocuments.receipt(audit.getValue()).matches(create));
        var filter = ArgumentCaptor.forClass(Bson.class);
        verify(h.collection(GUILDS)).updateOne(eq(h.session), filter.capture(), any(Bson.class), any(UpdateOptions.class));
        assertTrue(bson(filter.getValue()).contains("$$NOW")); assertTrue(bson(filter.getValue()).contains("$lt"));
        var order = inOrder(h.collection(AUDIT), h.collection(GUILDS));
        order.verify(h.collection(AUDIT)).insertOne(eq(h.session), any(Document.class));
        order.verify(h.collection(GUILDS)).updateOne(eq(h.session), any(Bson.class), any(Bson.class), any(UpdateOptions.class));
        verify(h.session).close();
    }
    @Test void replayDoesNotTouchAnyConfigurationGuardAndConflictingIntentIsRejected() {
        var h = fixture(initial());
        when(h.collection(AUDIT).find(eq(h.session), any(Bson.class))).thenAnswer(call -> find(ConfigurationDocuments.receipt(
                new Receipt("400", "300", "100", create.fingerprint(), 1, NOW), create)));
        var repository = new MongoConfigurationRepository(h.db);
        var originalProof = proof(create, GuildConfiguration.absent("100"));
        assertEquals(Status.REPLAYED, repository.commit(create, originalProof, policy).status());
        assertEquals(Status.IDEMPOTENCY_CONFLICT, repository.commit(new ConfigurationChange("400", "301", "100", 0, create.operation()), originalProof, policy).status());
        verify(h.collection(GUILDS), never()).findOneAndUpdate(any(), any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));
        verify(h.collection(AUDIT), never()).insertOne(any(), any(Document.class));
    }
    @Test void staleRevisionAndExpiredEvidenceNeverWriteProjections() {
        var h = fixture(initial()); var repository = new MongoConfigurationRepository(h.db);
        assertEquals(Status.REVISION_CONFLICT, repository.commit(create, proof(create, GuildConfiguration.absent("100")), policy).status());
        var edit = new ConfigurationChange("401", "300", "100", 1, new ConfigurationChange.SetGuildEnabled(false));
        var expected = proof(edit, initial());
        var expired = new ConfigurationAccess.Proof(expected.requirements(), NOW.minusSeconds(10), NOW);
        assertThrows(ConfigurationAccess.AccessDenied.class, () -> repository.commit(edit, expired, policy));
        verify(h.collection(CONFIGURATIONS), never()).replaceOne(any(), any(Bson.class), any(Document.class), any(ReplaceOptions.class));
        verify(h.collection(AUDIT), never()).insertOne(any(), any(Document.class));
    }
    @Test void removalRetainsIdentityTombstonesBeforeDeletingUniqueActiveSlots() {
        var before = initial(); var h = fixture(before);
        var remove = new ConfigurationChange("401", "300", "100", 1, new ConfigurationChange.RemoveDestination("200"));
        new MongoConfigurationRepository(h.db).commit(remove, proof(remove, before), policy);
        var tombstones = ArgumentCaptor.forClass(Document.class);
        verify(h.collection(TOMBSTONES), times(2)).insertOne(eq(h.session), tombstones.capture());
        assertTrue(tombstones.getAllValues().stream().allMatch(d -> d.getString("guildId").equals("100") && !d.containsKey("roleIds")));
        var filter = ArgumentCaptor.forClass(Bson.class);
        verify(h.collection(DESTINATIONS)).deleteOne(eq(h.session), filter.capture(), any(DeleteOptions.class));
        assertTrue(bson(filter.getValue()).contains(before.destinations().getFirst().ref().incarnation()));
        verify(h.collection(SUBSCRIPTIONS)).deleteOne(eq(h.session), any(Bson.class), any(DeleteOptions.class));
    }
    @Test void failedFinalFenceAndUnknownCommitNeverReturnSuccess() {
        var absent = GuildConfiguration.absent("100"); var h = fixture(absent);
        when(h.collection(GUILDS).updateOne(eq(h.session), any(Bson.class), any(Bson.class), any(UpdateOptions.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        assertThrows(ConfigurationAccess.AccessDenied.class, () -> new MongoConfigurationRepository(h.db).commit(create, proof(create, absent), policy));
        var unknown = new IllegalStateException("unknown commit");
        when(h.session.withTransaction(any(), any(TransactionOptions.class))).thenThrow(unknown);
        assertSame(unknown, assertThrows(IllegalStateException.class, () -> new MongoConfigurationRepository(h.db).commit(create, proof(create, absent), policy)));
    }
    @Test void transactionCallbackReplayKeepsStableIdsAndDoesNotInvokeDiscord() {
        var absent = GuildConfiguration.absent("100"); var h = fixture(absent);
        when(h.session.withTransaction(any(), any(TransactionOptions.class))).thenAnswer(call -> {
            TransactionBody<?> body = call.getArgument(0); body.execute(); return body.execute();
        });
        new MongoConfigurationRepository(h.db).commit(create, proof(create, absent), policy);
        var values = ArgumentCaptor.forClass(Document.class);
        verify(h.collection(DESTINATIONS), times(2)).insertOne(eq(h.session), values.capture());
        assertEquals(values.getAllValues().getFirst(), values.getAllValues().getLast());
    }
    @Test void codecRejectsForeignNestedReferencesAndRoundTripsBoundedState() {
        var before = initial(); var document = ConfigurationDocuments.configuration(before);
        assertEquals(before, ConfigurationDocuments.configuration(document));
        document.getList("destinations", Document.class).getFirst().getList("subscriptions", Document.class).getFirst().put("guildId", "101");
        assertThrows(IllegalArgumentException.class, () -> ConfigurationDocuments.configuration(document));
    }
}
