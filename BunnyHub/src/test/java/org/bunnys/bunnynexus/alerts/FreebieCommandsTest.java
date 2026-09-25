package org.bunnys.bunnynexus.alerts;

import java.time.*;
import java.util.*;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.bunnys.bunnynexus.alerts.application.*;
import org.bunnys.bunnynexus.alerts.domain.*;
import org.bunnys.bunnynexus.commands.alerts.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.bunnys.bunnynexus.alerts.domain.AlertIdentity.*;

class FreebieCommandsTest {
    final ConfigurationRepository repository = mock(ConfigurationRepository.class);
    final ConfigurationAccess access = mock(ConfigurationAccess.class);
    final Instant now = Instant.parse("2026-09-21T00:00:00Z");
    final ConfigurationPolicy policy = new ConfigurationPolicy(new ConfigurationPolicy.Limits(5, 10, 100,
            Set.of(new StoreId("epic")), Set.of(new Market("EG")), Set.of(Topic.FREE_GAME)));
    final ConfigurationService service = new ConfigurationService(repository, access, policy, Clock.fixed(now, ZoneOffset.UTC));
    final FreebieActions actions = new FreebieActions(service);

    SlashCommandInteractionEvent event() {
        var e = mock(SlashCommandInteractionEvent.class);
        var guild = mock(Guild.class); var member = mock(Member.class); var user = mock(User.class);
        when(e.isFromGuild()).thenReturn(true); when(e.getGuild()).thenReturn(guild); when(guild.getId()).thenReturn("10");
        when(e.getMember()).thenReturn(member); when(member.getGuild()).thenReturn(guild);
        when(member.getId()).thenReturn("20"); when(member.hasPermission(Permission.MANAGE_SERVER)).thenReturn(true);
        when(e.getUser()).thenReturn(user); when(user.getId()).thenReturn("20"); when(e.getId()).thenReturn("30");
        option(e, "revision", "0");
        return e;
    }
    void option(SlashCommandInteractionEvent e, String name, String value) {
        var option = mock(OptionMapping.class); when(option.getAsString()).thenReturn(value);
        when(option.getAsBoolean()).thenReturn(Boolean.parseBoolean(value)); when(e.getOption(name)).thenReturn(option);
    }
    void ready() {
        when(repository.load("10")).thenReturn(GuildConfiguration.absent("10"));
        when(repository.receipt(anyString(), anyString())).thenReturn(Optional.empty());
        when(access.verify(any())).thenAnswer(i -> new ConfigurationAccess.Proof(i.getArgument(0), now, now.plusSeconds(10)));
        when(repository.commit(any(), any(), any())).thenReturn(new ConfigurationRepository.Result(ConfigurationRepository.Status.APPLIED, 1));
    }
    void setup(SlashCommandInteractionEvent e) {
        option(e, "channel", "40"); option(e, "store", "epic"); option(e, "market", "EG");
        option(e, "roles", "50,50,60"); option(e, "enabled", "true");
    }
    @Test void definitionsArePrivateGuildOnlyAndRemainExplicit() {
        var command = new org.bunnys.bunnynexus.commands.alerts.FreebieCommand(null, service);
        assertFalse(command.isDmEnabled()); assertFalse(command.isMentionEnabled());
        assertTrue(command.defaultEphemeral(null)); assertTrue(command.isDeferBeforeDispatch());
        assertEquals(List.of(Permission.MANAGE_SERVER), command.getUserPermissions());
        assertEquals(Set.of("status", "setup", "toggle", "remove"), command.getSubcommands().keySet());
        assertNotNull(command.buildCommandData().toData());
    }
    @Test void rejectsDmMissingMemberForeignMemberAndRevokedPermissionBeforeReads() {
        var dm = event(); when(dm.isFromGuild()).thenReturn(false);
        var missing = event(); when(missing.getMember()).thenReturn(null);
        var foreign = event(); when(foreign.getMember().getId()).thenReturn("99");
        var revoked = event(); when(revoked.getMember().hasPermission(Permission.MANAGE_SERVER)).thenReturn(false);
        for (var e : List.of(dm, missing, foreign, revoked)) assertTrue(actions.run(e, "status").contains("Access could not"));
        verifyNoInteractions(repository, access);
    }
    @Test void cachedAccessIsCheckedAgainBeforeRead() {
        doThrow(new ConfigurationAccess.AccessDenied()).when(access).requireAdministrator("20", "10");
        assertTrue(actions.run(event(), "status").contains("Access could not")); verifyNoInteractions(repository);
    }
    @Test void setupUsesInteractionIdentityRevisionAndExactRoles() {
        ready(); var e = event(); setup(e);
        assertTrue(actions.run(e, "setup").startsWith("Settings saved"));
        var request = ArgumentCaptor.forClass(ConfigurationChange.class);
        verify(repository).commit(request.capture(), any(), same(policy));
        var change = request.getValue(); assertEquals("30", change.actionId()); assertEquals("20", change.actorId());
        assertEquals("10", change.guildId()); assertEquals(0, change.expectedRevision());
        var put = (ConfigurationChange.PutSubscription) change.operation();
        assertEquals(Set.of("50", "60"), put.roles()); assertEquals(Topic.FREE_GAME, put.topic());
    }
    @Test void staleRevisionNeverSilentlyOverwrites() {
        ready(); var e = event(); setup(e); option(e, "revision", "7");
        assertTrue(actions.run(e, "setup").contains("Settings changed")); verify(repository, never()).commit(any(), any(), any());
    }
    @Test void duplicateInteractionReplaysWithoutCommit() {
        ready(); var e = event(); setup(e);
        var change = new ConfigurationChange("30", "20", "10", 0, new ConfigurationChange.PutSubscription("40", new StoreId("epic"), new Market("EG"), Topic.FREE_GAME, true, Set.of("50", "60")));
        when(repository.receipt("10", "30")).thenReturn(Optional.of(new ConfigurationRepository.Receipt("30", "20", "10", change.fingerprint(), 1, now)));
        assertTrue(actions.run(e, "setup").contains("already saved")); verify(repository, never()).commit(any(), any(), any());
        option(e, "roles", "none"); assertTrue(actions.run(e, "setup").contains("does not match"));
    }
    @Test void removalDoesNotNeedDiscordChannelResolutionAndWarnsAboutAuthorizedSends() {
        ready(); var e = event(); setup(e);
        var change = new ConfigurationChange("29", "20", "10", 0, new ConfigurationChange.PutSubscription("40", new StoreId("epic"), new Market("EG"), Topic.FREE_GAME, true, Set.of()));
        when(repository.load("10")).thenReturn(policy.apply(GuildConfiguration.absent("10"), change, now));
        option(e, "scope", "channel"); when(e.getOption("store")).thenReturn(null); option(e, "revision", "1");
        assertTrue(actions.run(e, "remove").contains("already-authorized"));
        var proof = ArgumentCaptor.forClass(ConfigurationAccess.Requirements.class); verify(access).verify(proof.capture());
        assertTrue(proof.getValue().destinations().isEmpty());
    }
    @Test void ambiguousScopeAndInvalidRolesCannotWrite() {
        var e = event(); setup(e); option(e, "scope", "server");
        assertTrue(actions.run(e, "remove").contains("Server scope"));
        option(e, "roles", "@everyone"); assertTrue(actions.run(e, "setup").contains("Invalid settings"));
        verifyNoInteractions(repository);
    }
    @Test void everyToggleAndRemovalScopeMapsToTheReviewedIntent() {
        var serviceDouble = mock(ConfigurationService.class);
        when(serviceDouble.change(any())).thenReturn(new ConfigurationRepository.Result(ConfigurationRepository.Status.APPLIED, 8));
        var boundary = new FreebieActions(serviceDouble);
        for (String scope : List.of("server", "channel", "store")) {
            for (String action : List.of("toggle", "remove")) {
                var e = event(); option(e, "revision", "7"); option(e, "scope", scope); option(e, "enabled", "false");
                if (!scope.equals("server")) option(e, "channel", "40");
                if (scope.equals("store")) option(e, "store", "epic");
                assertTrue(boundary.run(e, action).contains("already-authorized"));
            }
        }
        var changes = ArgumentCaptor.forClass(ConfigurationChange.class);
        verify(serviceDouble, times(6)).change(changes.capture());
        assertEquals(List.of(ConfigurationChange.SetGuildEnabled.class, ConfigurationChange.RemoveGuild.class,
                ConfigurationChange.SetDestinationEnabled.class, ConfigurationChange.RemoveDestination.class,
                ConfigurationChange.SetSubscriptionEnabled.class, ConfigurationChange.RemoveSubscription.class),
                changes.getAllValues().stream().map(c -> c.operation().getClass()).toList());
        assertTrue(changes.getAllValues().stream().allMatch(c -> c.expectedRevision() == 7));
    }
    @Test void administratorAndEmptyRolesAreSupportedButEveryoneIsRejected() {
        ready(); var e = event(); setup(e);
        when(e.getMember().hasPermission(Permission.MANAGE_SERVER)).thenReturn(false);
        when(e.getMember().hasPermission(Permission.ADMINISTRATOR)).thenReturn(true);
        option(e, "roles", "none"); assertTrue(actions.run(e, "setup").startsWith("Settings saved"));
        option(e, "roles", "10"); assertTrue(actions.run(e, "setup").contains("Invalid settings"));
        verify(repository, times(1)).commit(any(), any(), any());
    }
    @Test void unknownCommitDoesNotClaimFailureOrSuccessOrLeakDetails() {
        ready(); var e = event(); setup(e);
        when(repository.commit(any(), any(), any())).thenThrow(new IllegalStateException("secret MongoURI"));
        String message = actions.run(e, "setup"); assertTrue(message.contains("outcome could not be confirmed"));
        assertFalse(message.contains("secret")); assertTrue(message.contains("30"));
    }
    @Test void repositoryValidationFailureIsNotBlamedOnUserInput() {
        ready(); var e = event(); setup(e);
        when(repository.commit(any(), any(), any())).thenThrow(new IllegalArgumentException("private database payload"));
        assertTrue(actions.run(e, "setup").contains("outcome could not be confirmed"));
        when(repository.load("10")).thenThrow(new IllegalArgumentException("private stored record"));
        assertTrue(actions.run(e, "status").contains("outcome could not be confirmed"));
    }
    @Test void missingBooleanIsInputFailureBeforeStorageAccess() {
        var e = event(); setup(e); when(e.getOption("enabled")).thenReturn(null);
        assertTrue(actions.run(e, "setup").contains("Invalid settings")); verifyNoInteractions(repository);
    }
    @Test void statusPagesCrossEmptyDestinationsAndRoleChunksWithoutDroppingSettings() {
        var roles = new HashSet<String>(); for (int i = 100; i < 126; i++) roles.add(Integer.toString(i));
        var setting = new GuildConfiguration.Setting("s", new StoreId("epic"), new Market("EG"), Topic.FREE_GAME, true, now, 1, roles);
        var empty = new GuildConfiguration.Destination(new DestinationRef("10", "40", "a", "a"), 1, false, List.of());
        var full = new GuildConfiguration.Destination(new DestinationRef("10", "41", "b", "b"), 1, true, List.of(setting));
        var config = new GuildConfiguration("10", 2, true, false, List.of(empty, full));
        assertTrue(FreebieSettings.render(config, 1).contains("No store settings"));
        assertTrue(FreebieSettings.render(config, 2).contains("roles 1-25/26"));
        assertTrue(FreebieSettings.render(config, 3).contains("125 (roles 26-26/26)"));
    }
    @Test void maximumRoleAndIdentifierSettingsRemainReadableAcrossBoundedPages() {
        var roles = new HashSet<String>(); for (long n = 100; n < 200; n++) roles.add("10000000000000000" + n);
        var setting = new GuildConfiguration.Setting("id", new StoreId("@".repeat(256)), new Market("*".repeat(256)), Topic.FREE_GAME, true, now, 1, roles);
        var destination = new GuildConfiguration.Destination(new DestinationRef("10", "40", "dest", "inc"), 1, true, List.of(setting));
        var config = new GuildConfiguration("10", 1, true, false, List.of(destination));
        for (int page = 1; page <= 4; page++) {
            String text = FreebieSettings.render(config, page); assertTrue(text.length() <= 2000); assertFalse(text.contains("@"));
            assertTrue(text.contains("Page " + page + "/4"));
        }
        assertThrows(org.bunnys.handler.utils.InteractionErrors.InputFailure.class, () -> FreebieSettings.render(config, 5));
    }
}
