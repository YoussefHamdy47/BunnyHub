package org.bunnys.modals;

import net.dv8tion.jda.api.entities.Guild;
import org.bunnys.bunnynexus.setup.ServerSettings;
import org.bunnys.bunnynexus.setup.ServerSetupTest;
import org.bunnys.bunnynexus.setup.ServerSettings.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SetupModalTest {
    private static final String CHANNEL = "223456789012345678", ROLE = "323456789012345678";

    @Test void emptyChannelTurnsAMessageOffButKeepsItsText() {
        Guild guild = guild(true, true);
        var on = SetupModal.saveGreeting(guild, ServerSettings.empty(), Kind.WELCOME, List.of(CHANNEL), "Hi {user}");
        assertEquals(CHANNEL, on.settings().welcome().channelId());
        assertEquals("Hi {user}", on.settings().welcome().message());
        assertTrue(on.notice().contains("<#" + CHANNEL + ">"));

        var off = SetupModal.saveGreeting(guild, on.settings(), Kind.WELCOME, List.of(), "Hi {user}");
        assertFalse(off.settings().welcome().on());
        assertEquals("Hi {user}", off.settings().welcome().message());
        assertTrue(off.notice().contains("off"));
    }

    @Test void theDefaultTextIsNotPinnedAsCustom() {
        var saved = SetupModal.saveGreeting(guild(true, true), ServerSettings.empty(), Kind.GOODBYE, List.of(CHANNEL),
                Kind.GOODBYE.defaultMessage());
        assertNull(saved.settings().goodbye().message());
    }

    @Test void channelTheBotCannotUseIsSavedWithAWarning() {
        var saved = SetupModal.saveGreeting(guild(false, true), ServerSettings.empty(), Kind.WELCOME, List.of(CHANNEL), "");
        assertTrue(saved.settings().welcome().on());
        assertTrue(saved.notice().contains("I can't post there yet"), saved.notice());
    }

    @Test void rolesTheBotCannotGiveAreRefusedWithAReason() {
        var refused = SetupModal.saveRoles(guild(true, false), ServerSettings.empty(), List.of(ROLE));
        assertTrue(refused.settings().joinRoles().isEmpty());
        assertTrue(refused.notice().contains("above my highest role"), refused.notice());

        var kept = SetupModal.saveRoles(guild(true, true), ServerSettings.empty(), List.of(ROLE, ROLE));
        assertEquals(List.of(ROLE), kept.settings().joinRoles());
    }

    private static Guild guild(boolean canPost, boolean canGiveRole) {
        return ServerSetupTest.guild(canPost, canGiveRole);
    }
}
