package org.bunnys.bunnynexus.timers.services;

import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import org.bunnys.database.models.timers.TimerData;
import org.bunnys.bunnynexus.timers.buttons.SessionMenuManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
class SessionMenuTest {
    @Test void oldPendingMenuCannotConsumeReplacement() {
        InteractionHook hook = mock(InteractionHook.class);
        when(hook.editOriginalComponents()).thenReturn(mock(WebhookMessageEditAction.class));
        try {
            PendingSessionManager.createPendingSession("123", "CS-101", null, "channel", "DM", hook, "old");
            PendingSessionManager.createPendingSession("123", "CS-101", null, "channel", "DM", hook, "new");
            assertFalse(PendingSessionManager.matches("123", "old"));
            assertNull(PendingSessionManager.getAndRemove("123", "old"));
            assertTrue(PendingSessionManager.matches("123", "new"));
            assertNotNull(PendingSessionManager.getAndRemove("123", "new"));
            assertFalse(PendingSessionManager.matches("123", "new"));
        } finally { PendingSessionManager.cancelPendingSession("123"); }
    }

    @Test void oldActiveMenuCannotStopCurrentSession() {
        var event = mock(ButtonInteractionEvent.class);
        var user = mock(User.class);
        var hook = mock(InteractionHook.class);
        var response = mock(WebhookMessageCreateAction.class, RETURNS_SELF);
        when(event.getUser()).thenReturn(user);
        when(user.getId()).thenReturn("123");
        when(event.getMessageId()).thenReturn("old");
        when(event.getHook()).thenReturn(hook);
        when(hook.sendMessage(anyString())).thenReturn(response);
        var timer = new TimerData();
        timer.getSessionData().setMessageID("new");
        try (var service = mockStatic(TimerSessionService.class)) {
            service.when(() -> TimerSessionService.getTimerDataOrThrow("123")).thenReturn(timer);
            SessionMenuManager.handle(event, "end", "123");
            service.verify(() -> TimerSessionService.stopSession("123", event), never());
            verify(hook).sendMessage(contains("expired"));
        }
    }
}
