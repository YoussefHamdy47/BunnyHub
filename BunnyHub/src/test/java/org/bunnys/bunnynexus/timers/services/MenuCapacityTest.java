package org.bunnys.bunnynexus.timers.services;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import org.bunnys.bunnynexus.timers.buttons.GPAPaginator;
import org.bunnys.handler.utils.InteractionErrors;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MenuCapacityTest {
    @Test void pendingOverloadPreservesExistingMenusAndAllowsOwnerReplacement() {
        var hook = mock(InteractionHook.class);
        when(hook.editOriginalComponents()).thenReturn(mock(WebhookMessageEditAction.class));
        try {
            for (int i = 0; i < PendingSessionManager.MAX_SESSIONS; i++)
                assertTrue(PendingSessionManager.createPendingSession("capacity-" + i, "CS", null, "channel", "DM", hook, "old"));
            assertFalse(PendingSessionManager.createPendingSession("overflow", "CS", null, "channel", "DM", hook, "new"));
            assertTrue(PendingSessionManager.createPendingSession("capacity-0", "CS", null, "channel", "DM", hook, "replacement"));
            assertFalse(PendingSessionManager.matches("capacity-0", "old"));
            assertNotNull(PendingSessionManager.getAndRemove("capacity-0", "replacement"));
            assertTrue(PendingSessionManager.createPendingSession("overflow", "CS", null, "channel", "DM", hook, "new"));
        } finally {
            for (int i = 0; i < PendingSessionManager.MAX_SESSIONS; i++) PendingSessionManager.cancelPendingSession("capacity-" + i);
            PendingSessionManager.cancelPendingSession("overflow");
        }
    }

    @Test void gpaBoundsOwnersTotalAndPagesAndReclaimsDiscardedCapacity() {
        var pages = List.of(new EmbedBuilder().setDescription("Academic record").build());
        var ids = new ArrayList<String>();
        try {
            for (int i = 0; i < GPAPaginator.MAX_SESSIONS_PER_USER; i++) ids.add(GPAPaginator.createSession("owner", pages));
            assertThrows(InteractionErrors.StateFailure.class, () -> GPAPaginator.createSession("owner", pages));
            assertThrows(InteractionErrors.StateFailure.class, () -> GPAPaginator.createSession("large", Collections.nCopies(GPAPaginator.MAX_PAGES + 1, pages.getFirst())));
            for (int i = ids.size(); i < GPAPaginator.MAX_SESSIONS; i++) ids.add(GPAPaginator.createSession("owner-" + i, pages));
            assertThrows(InteractionErrors.StateFailure.class, () -> GPAPaginator.createSession("overflow", pages));
            GPAPaginator.discardSession(ids.getFirst());
            GPAPaginator.discardSession(ids.getFirst()); // A late failure must not release another menu.
            ids.add(GPAPaginator.createSession("owner", pages));
            assertThrows(InteractionErrors.StateFailure.class, () -> GPAPaginator.createSession("overflow", pages));
        } finally { ids.forEach(GPAPaginator::discardSession); }
    }
}
