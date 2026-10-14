package org.bunnys.events;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.unions.ChannelUnion;
import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent;
import net.dv8tion.jda.api.events.role.RoleDeleteEvent;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FreebieDeletionEventsTest {
    final FreebieSystem system = mock(FreebieSystem.class);
    final Guild guild = mock(Guild.class);

    ChannelDeleteEvent channelEvent(boolean fromGuild, ChannelType type) {
        var event = mock(ChannelDeleteEvent.class);
        var channel = mock(ChannelUnion.class);
        when(event.isFromGuild()).thenReturn(fromGuild);
        when(event.getChannelType()).thenReturn(type);
        when(event.getGuild()).thenReturn(guild);
        when(event.getChannel()).thenReturn(channel);
        when(guild.getId()).thenReturn("100000000000000000");
        when(channel.getId()).thenReturn("200000000000000000");
        when(channel.getName()).thenReturn("epic-games");
        return event;
    }

    @Test void deletedGuildChannelIsHandedToTheFreebieSystem() {
        FreebieChannelDelete.handle(system, channelEvent(true, ChannelType.TEXT));
        FreebieChannelDelete.handle(system, channelEvent(true, ChannelType.NEWS));
        verify(system, times(2)).channelDeleted("100000000000000000", "200000000000000000", "epic-games");
    }

    @Test void directMessagesAndThreadsAreIgnored() {
        FreebieChannelDelete.handle(system, channelEvent(false, ChannelType.PRIVATE));
        FreebieChannelDelete.handle(system, channelEvent(true, ChannelType.GUILD_PUBLIC_THREAD));
        verify(system, never()).channelDeleted(any(), any(), any());
    }

    @Test void deletedRoleIsHandedToTheFreebieSystem() {
        var event = mock(RoleDeleteEvent.class);
        var role = mock(Role.class);
        when(event.getGuild()).thenReturn(guild);
        when(event.getRole()).thenReturn(role);
        when(guild.getId()).thenReturn("100000000000000000");
        when(role.getId()).thenReturn("300000000000000000");
        when(role.getName()).thenReturn("Steam");
        FreebieRoleDelete.handle(system, event);
        verify(system).roleDeleted("100000000000000000", "300000000000000000", "Steam");
        verify(system, never()).channelDeleted(any(), any(), any());
    }
}
