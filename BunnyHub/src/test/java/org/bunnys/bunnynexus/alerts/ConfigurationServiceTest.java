package org.bunnys.bunnynexus.alerts;

import org.bunnys.bunnynexus.alerts.application.*;
import org.bunnys.bunnynexus.alerts.domain.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;
import static org.bunnys.bunnynexus.alerts.application.ConfigurationRepository.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfigurationServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");
    private final ConfigurationRepository repository = mock(ConfigurationRepository.class);
    private final ConfigurationAccess access = mock(ConfigurationAccess.class);
    private final ConfigurationPolicy policy = new ConfigurationPolicy(new ConfigurationPolicy.Limits(2, 4, 2,
            Set.of(new StoreId("epic")), Set.of(new Market("EG")), Set.of(Topic.FREE_GAME)));
    private final ConfigurationService service = new ConfigurationService(repository, access, policy, Clock.fixed(NOW, ZoneOffset.UTC));
    private final ConfigurationChange change = new ConfigurationChange("400", "300", "100", 0,
            new ConfigurationChange.PutSubscription("200", new StoreId("epic"), new Market("EG"), Topic.FREE_GAME, true, Set.of("500")));
    private void absent() {
        when(repository.receipt("100", "400")).thenReturn(Optional.empty());
        when(repository.load("100")).thenReturn(GuildConfiguration.absent("100"));
    }
    @Test void unauthorizedReadsAndWritesNeverReachStorage() {
        doThrow(new ConfigurationAccess.AccessDenied()).when(access).requireAdministrator("300", "100");
        assertThrows(ConfigurationAccess.AccessDenied.class, () -> service.view("300", "100"));
        assertThrows(ConfigurationAccess.AccessDenied.class, () -> service.change(change));
        verifyNoInteractions(repository);
    }
    @Test void accessIsBoundToTheExactGuildChannelAndRolesBeforeCommit() {
        absent();
        when(access.verify(any())).thenAnswer(call -> {
            ConfigurationAccess.Requirements requirements = call.getArgument(0);
            assertEquals("100", requirements.guildId()); assertEquals("300", requirements.actorId());
            assertEquals(List.of(new ConfigurationAccess.DestinationRequirement("200", Set.of("500"))), requirements.destinations());
            return new ConfigurationAccess.Proof(requirements, NOW, NOW.plusSeconds(10));
        });
        when(repository.commit(eq(change), any(), eq(policy))).thenReturn(new Result(Status.APPLIED, 1));
        assertEquals(Status.APPLIED, service.change(change).status());
        var order = inOrder(access, repository);
        order.verify(access).requireAdministrator("300", "100");
        order.verify(repository).receipt("100", "400"); order.verify(repository).load("100");
        order.verify(access).verify(any()); order.verify(repository).commit(eq(change), any(), eq(policy));
    }
    @Test void expiredOrForeignEvidenceNeverReachesCommit() {
        absent();
        var expected = ConfigurationAccess.requirements(change, policy.apply(GuildConfiguration.absent("100"), change, NOW));
        for (var proof : List.of(new ConfigurationAccess.Proof(expected, NOW.minusSeconds(10), NOW),
                new ConfigurationAccess.Proof(new ConfigurationAccess.Requirements(expected.fingerprint(), "301", "100", expected.destinations()), NOW, NOW.plusSeconds(10)),
                new ConfigurationAccess.Proof(expected, NOW.plusSeconds(1), NOW.plusSeconds(10)))) {
            when(access.verify(any())).thenReturn(proof);
            assertThrows(ConfigurationAccess.AccessDenied.class, () -> service.change(change));
        }
        verify(repository, never()).commit(any(), any(), any());
    }
    @Test void replayRequiresCurrentAdministratorButDoesNotRepeatMutation() {
        when(repository.receipt("100", "400")).thenReturn(Optional.of(new Receipt("400", "300", "100", change.fingerprint(), 1, NOW)));
        assertEquals(Status.REPLAYED, service.change(change).status());
        assertEquals(Status.IDEMPOTENCY_CONFLICT, service.change(new ConfigurationChange("400", "300", "100", 0, new ConfigurationChange.RemoveGuild())).status());
        verify(repository, never()).load(any()); verify(repository, never()).commit(any(), any(), any());
        verify(access, times(2)).requireAdministrator("300", "100");
    }
    @Test void disableCanProceedWithoutResolvingADeletedDiscordChannel() {
        var current = policy.apply(GuildConfiguration.absent("100"), change, NOW);
        var disable = new ConfigurationChange("401", "300", "100", 1, new ConfigurationChange.SetGuildEnabled(false));
        when(repository.receipt("100", "401")).thenReturn(Optional.empty()); when(repository.load("100")).thenReturn(current);
        when(access.verify(any())).thenAnswer(call -> {
            ConfigurationAccess.Requirements requirements = call.getArgument(0); assertTrue(requirements.destinations().isEmpty());
            return new ConfigurationAccess.Proof(requirements, NOW, NOW.plusSeconds(10));
        });
        when(repository.commit(eq(disable), any(), eq(policy))).thenReturn(new Result(Status.APPLIED, 2));
        assertEquals(Status.APPLIED, service.change(disable).status());
    }
}
