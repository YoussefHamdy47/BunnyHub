package org.bunnys.handler;

import net.dv8tion.jda.api.interactions.IntegrationType;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import org.bunnys.commands.Avatar;
import org.bunnys.commands.Help;
import org.bunnys.commands.ModerationCommand;
import org.bunnys.commands.Ping;
import org.bunnys.commands.Timer;
import org.bunnys.commands.Uptime;
import org.bunnys.commands.freebies.FreeGames;
import org.bunnys.commands.info.Info;
import org.bunnys.commands.freebies.FreebieAdmin;
import org.bunnys.handler.commands.BunnyCommand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InstallTypesTest {
    @Test void serverToolsStayServerInstalled() {
        for (BunnyCommand command : List.of(new ModerationCommand(null), new Timer(null))) {
            assertFalse(command.isUserInstallable(), command.getName());
            assertEquals(Set.of(IntegrationType.GUILD_INSTALL), command.buildCommandData().getIntegrationTypes(), command.getName());
        }
    }

    @Test void everydayCommandsAlsoWorkFromAnAccountInstall() {
        for (BunnyCommand command : List.of(new Ping(null), new Uptime(null), new Avatar(null), new Info(null),
                new Help(null), new FreeGames(null))) {
            assertEquals(IntegrationType.ALL, command.buildCommandData().getIntegrationTypes(), command.getName());
            for (var definition : command.buildCommandDefinitions())
                assertEquals(IntegrationType.ALL, definition.getIntegrationTypes(), command.getName() + " " + definition.getName());
        }
    }

    @Test void ownerCommandsLiveOnlyOnDevelopersAccounts() {
        var admin = new FreebieAdmin(null);
        assertTrue(admin.isDeveloperOnly() && admin.isUserInstallable());
        var data = admin.buildCommandData();
        assertEquals(Set.of(IntegrationType.USER_INSTALL), data.getIntegrationTypes());
        assertTrue(data.getContexts().contains(InteractionContextType.GUILD), "usable in any server");
        assertNull(data.getDefaultPermissions().getPermissionsRaw(), "server permission defaults don't apply to account installs");
        assertTrue(admin.getUserPermissions().isEmpty(), "would block developers who aren't admins in the server they're in");
    }

    @Test void installTypesValidate() {
        var command = new BunnyCommand(null) {};
        assertThrows(IllegalArgumentException.class, command::setInstallTypes);
        assertThrows(IllegalArgumentException.class, () -> command.setInstallTypes(IntegrationType.UNKNOWN));
        command.setName("both").setDescription("Both");
        command.setInstallTypes(IntegrationType.GUILD_INSTALL, IntegrationType.USER_INSTALL);
        assertEquals(IntegrationType.ALL, command.buildCommandData().getIntegrationTypes());
    }
}
