package org.bunnys.handler;

import net.dv8tion.jda.api.interactions.IntegrationType;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import org.bunnys.commands.Ping;
import org.bunnys.commands.freebies.FreebieAdmin;
import org.bunnys.handler.commands.BunnyCommand;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InstallTypesTest {
    @Test void commandsAreServerInstalledByDefault() {
        var ping = new Ping(null);
        assertFalse(ping.isUserInstallable());
        assertEquals(Set.of(IntegrationType.GUILD_INSTALL), ping.buildCommandData().getIntegrationTypes());
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
