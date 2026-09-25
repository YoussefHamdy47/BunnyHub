package org.bunnys.bunnynexus.alerts;

import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.bunnys.bunnynexus.alerts.adapters.discord.JdaConfigurationAccess;
import org.bunnys.bunnynexus.alerts.application.ConfigurationAccess.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdaConfigurationAccessTest {
    private final JDA jda = mock(JDA.class);
    private final Guild guild = mock(Guild.class);
    private final Member actor = mock(Member.class);
    private final SelfMember bot = mock(SelfMember.class);
    private final TextChannel channel = mock(TextChannel.class);
    private final Role role = mock(Role.class);
    private final JdaConfigurationAccess access = new JdaConfigurationAccess(jda,
            Clock.fixed(Instant.parse("2026-09-14T10:00:00Z"), ZoneOffset.UTC), Duration.ofSeconds(10), Set.of(ChannelType.TEXT));
    private final Requirements requirements = new Requirements("a".repeat(64), "300", "100", List.of(new DestinationRequirement("200", Set.of("500"))));
    private void allowed() {
        when(jda.getStatus()).thenReturn(JDA.Status.CONNECTED); when(jda.getGuildById("100")).thenReturn(guild);
        when(guild.getId()).thenReturn("100"); when(guild.getMemberById("300")).thenReturn(actor);
        when(actor.getGuild()).thenReturn(guild); when(actor.hasPermission(Permission.MANAGE_SERVER)).thenReturn(true);
        when(guild.getSelfMember()).thenReturn(bot); when(guild.getGuildChannelById("200")).thenReturn(channel);
        when(channel.getGuild()).thenReturn(guild); when(channel.getType()).thenReturn(ChannelType.TEXT);
        when(bot.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)).thenReturn(true);
        when(guild.getRoleById("500")).thenReturn(role); when(role.getGuild()).thenReturn(guild); when(role.isMentionable()).thenReturn(true);
    }
    @Test void acceptsExplicitMentionableRolesWithoutRequiringBroadMentionPermission() {
        allowed(); assertEquals(requirements, access.verify(requirements).requirements());
        verify(bot, never()).hasPermission(channel, Permission.MESSAGE_MENTION_EVERYONE);
    }
    @Test void revokedAuthorityMissingMembersAndDisconnectedCacheDeny() {
        allowed(); when(actor.hasPermission(Permission.MANAGE_SERVER)).thenReturn(false);
        assertThrows(AccessDenied.class, () -> access.verify(requirements));
        when(actor.hasPermission(Permission.ADMINISTRATOR)).thenReturn(true);
        assertDoesNotThrow(() -> access.verify(requirements));
        when(guild.getMemberById("300")).thenReturn(null);
        assertThrows(AccessDenied.class, () -> access.requireAdministrator("300", "100"));
        when(jda.getStatus()).thenReturn(JDA.Status.DISCONNECTED);
        assertThrows(AccessDenied.class, () -> access.requireAdministrator("300", "100"));
    }
    @Test void foreignRolesUnmentionableRolesAndUnsupportedChannelsDeny() {
        allowed(); var foreign = mock(Guild.class); when(foreign.getId()).thenReturn("101"); when(role.getGuild()).thenReturn(foreign);
        assertThrows(AccessDenied.class, () -> access.verify(requirements));
        when(role.getGuild()).thenReturn(guild); when(role.isMentionable()).thenReturn(false);
        assertThrows(AccessDenied.class, () -> access.verify(requirements));
        when(role.isMentionable()).thenReturn(true); when(channel.getType()).thenReturn(ChannelType.NEWS);
        assertThrows(AccessDenied.class, () -> access.verify(requirements));
        when(channel.getType()).thenReturn(ChannelType.TEXT); when(channel.getGuild()).thenReturn(foreign);
        assertThrows(AccessDenied.class, () -> access.verify(requirements));
    }
    @Test void missingBotCapabilityAndTimeoutDenyWhileDisableNeedsNoChannel() {
        allowed(); when(bot.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)).thenReturn(false);
        assertThrows(AccessDenied.class, () -> access.verify(requirements));
        when(bot.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)).thenReturn(true);
        when(bot.isTimedOut()).thenReturn(true);
        assertThrows(AccessDenied.class, () -> access.verify(requirements));
        assertDoesNotThrow(() -> access.verify(new Requirements(requirements.fingerprint(), "300", "100", List.of())));
    }
}
