package org.bunnys.bunnynexus.timers.services;

import net.dv8tion.jda.api.entities.MessageEmbed;
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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"pause", "resume"})
    void breakNotesAreEphemeralWithoutScheduledDeletion(String action) {
        var event = mock(ButtonInteractionEvent.class, RETURNS_DEEP_STUBS);
        when(event.getUser().getId()).thenReturn("123");
        when(event.getMessageId()).thenReturn("current");
        var card = new net.dv8tion.jda.api.EmbedBuilder().setDescription("Session").build();
        when(event.getMessage().getEmbeds()).thenReturn(java.util.List.of(card));
        var hook = mock(InteractionHook.class);
        when(event.getHook()).thenReturn(hook);
        var edit = mock(WebhookMessageEditAction.class, RETURNS_SELF);
        var response = mock(WebhookMessageCreateAction.class, RETURNS_SELF);
        when(hook.editOriginalEmbeds(any(MessageEmbed.class))).thenReturn(edit);
        when(hook.sendMessageEmbeds(any(MessageEmbed.class))).thenReturn(response);
        doAnswer(invocation -> {
            java.util.function.Consumer callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(edit).queue(any(java.util.function.Consumer.class));
        var timer = new TimerData();
        timer.getSessionData().setMessageID("current");
        timer.getSessionData().setSessionStartTime(new java.util.Date());
        try (var service = mockStatic(TimerSessionService.class);
             var timers = mockConstruction(org.bunnys.bunnynexus.timers.Timers.class, (mock, context) -> {
                 when(mock.session()).thenReturn(timer.getSessionData());
                 when(mock.refreshMainEmbed(card)).thenReturn(card);
             })) {
            service.when(() -> TimerSessionService.getTimerDataOrThrow("123")).thenReturn(timer);
            SessionMenuManager.handle(event, action, "123");
            verify(response).setEphemeral(true);
            verify(response).queue();
            verify(response, never()).queue(any(java.util.function.Consumer.class));
            assertEquals(1, timers.constructed().size());
        }
    }

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
        when(event.isAcknowledged()).thenReturn(true); // the router defers the edit before the handler runs
        when(hook.sendMessageEmbeds(any(MessageEmbed.class))).thenReturn(response);
        var timer = new TimerData();
        timer.getSessionData().setMessageID("new");
        try (var service = mockStatic(TimerSessionService.class)) {
            service.when(() -> TimerSessionService.getTimerDataOrThrow("123")).thenReturn(timer);
            SessionMenuManager.handle(event, "end", "123");
            service.verify(() -> TimerSessionService.stopSession("123", event), never());
            verify(hook).sendMessageEmbeds(argThat((MessageEmbed embed) -> embed.getDescription().contains("expired")));
        }
    }
}
