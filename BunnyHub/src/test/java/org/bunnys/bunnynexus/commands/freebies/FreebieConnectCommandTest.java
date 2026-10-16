package org.bunnys.bunnynexus.commands.freebies;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FreebieConnectCommandTest {
    @Test void findsRoleByIdOrNameButNeverEveryone() {
        Guild guild = mock(Guild.class);
        Role freeGames = mock(Role.class), everyone = mock(Role.class);
        when(everyone.isPublicRole()).thenReturn(true);
        when(guild.getRoleById("111111111111111111")).thenReturn(freeGames);
        when(guild.getRoleById("222222222222222222")).thenReturn(everyone);
        when(guild.getRolesByName("free games", true)).thenReturn(List.of(freeGames));
        when(guild.getRolesByName("@everyone", true)).thenReturn(List.of(everyone));

        assertSame(freeGames, FreebieConnectCommand.findRole(guild, "111111111111111111").orElseThrow());
        assertSame(freeGames, FreebieConnectCommand.findRole(guild, "free games").orElseThrow());
        assertTrue(FreebieConnectCommand.findRole(guild, "222222222222222222").isEmpty());
        assertTrue(FreebieConnectCommand.findRole(guild, "@everyone").isEmpty());
        assertTrue(FreebieConnectCommand.findRole(guild, "333333333333333333").isEmpty());
    }

    @Test void commandBuilds() {
        var command = new FreebieConnectCommand();
        assertEquals("connect", command.getName());
        assertEquals(4, command.getOptions().size());
    }
}
