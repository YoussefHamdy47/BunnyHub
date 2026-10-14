package org.bunnys.buttons;

import net.dv8tion.jda.api.entities.User;
import org.bunnys.bunnynexus.freebies.FreebieDm;
import org.bunnys.bunnynexus.freebies.FreebieDmService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The Unsubscribe button only ever removes the clicker's own subscription. */
class FreebieDmButtonsTest {
    @Test void unsubscribesTheClickerOnly() {
        var dms = mock(FreebieDmService.class);
        var clicker = mock(User.class);
        when(clicker.getId()).thenReturn("400000000000000001");
        when(dms.unsubscribe("400000000000000001")).thenReturn(true);
        assertTrue(FreebieDmButtons.unsubscribe(dms, clicker).startsWith("Done."));
        verify(dms).unsubscribe("400000000000000001");
        verifyNoMoreInteractions(dms);
        when(dms.unsubscribe("400000000000000001")).thenReturn(false);
        assertEquals("You aren't subscribed to free-game DMs.", FreebieDmButtons.unsubscribe(dms, clicker));
    }

    @Test void theButtonIdCarriesNoUserSoItCannotTargetSomeoneElse() {
        assertEquals(FreebieDm.UNSUBSCRIBE_PREFIX, FreebieDm.unsubscribeButton().getCustomId());
        assertEquals(FreebieDm.UNSUBSCRIBE_PREFIX, new FreebieDmButtons().getPrefix());
    }
}
