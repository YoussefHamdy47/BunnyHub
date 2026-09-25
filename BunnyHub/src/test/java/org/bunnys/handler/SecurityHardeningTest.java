package org.bunnys.handler;

import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.requests.restaction.interactions.AutoCompleteCallbackAction;
import org.bunnys.events.InteractionListener;
import org.bunnys.handler.commands.*;
import org.bunnys.handler.commands.context.CommandContext;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecurityHardeningTest {
    @Test void openingConfirmationFormDoesNotReadDatabase() {
        try (var database = mockStatic(org.bunnys.handler.database.DB.class)) {
            var timers = new org.bunnys.bunnynexus.timers.Timers("123", null);
            var modal = timers.buildEndSemesterModal("7");
            assertNotNull(modal.toData());
            assertTrue(modal.getId().startsWith("semester_end_modal:123:"));
            database.verifyNoInteractions();
        }
    }

    @Test void restrictedAutocompleteDoesNotCallProvider() {
        var hub = mock(BunnyHub.class);
        var registry = new CommandRegistry(hub, List.of(), List.of());
        var command = spy(new BunnyCommand(hub) {});
        command.setName("private"); command.setDescription("Private command"); command.setDeveloperOnly(true);
        registry.registerCommand(command);
        when(hub.getCommandRegistry()).thenReturn(registry);
        doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; })
                .when(hub).executeAutocomplete(anyString(), any());
        var event = mock(CommandAutoCompleteInteractionEvent.class);
        var user = mock(User.class);
        when(user.getId()).thenReturn("unauthorized");
        when(event.getUser()).thenReturn(user);
        when(event.getName()).thenReturn("private");
        var reply = mock(AutoCompleteCallbackAction.class);
        when(event.replyChoiceStrings(anyCollection())).thenReturn(reply);
        new InteractionListener(hub).onCommandAutoCompleteInteraction(event);
        verify(command, never()).autocomplete(any(), any());
        verify(event).replyChoiceStrings(List.of());
        verify(reply).queue();
    }

    @Test void checkingReadAccessDoesNotConsumeExecutionCooldown() {
        var command = new BunnyCommand(null) {};
        command.setName("allowed"); command.setCooldown(60);
        var registry = new CommandRegistry(null, List.of(), List.of());
        var ctx = mock(CommandContext.class, CALLS_REAL_METHODS);
        var user = mock(User.class);
        when(user.getId()).thenReturn(UUID.randomUUID().toString());
        when(ctx.getUser()).thenReturn(user);
        assertNull(CommandGate.checkAccess(ctx, registry, command, null));
        assertNull(CommandGate.checkAccess(ctx, registry, command, null));
        assertNull(CommandGate.check(ctx, registry, command, null));
        assertNotNull(CommandGate.check(ctx, registry, command, null));
        assertNull(CommandGate.checkAccess(ctx, registry, command, null));
    }

    @Test void perUserLimitLeavesCapacityForOtherUsersAndRecovers() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var other = new CountDownLatch(1);
        var drained = new CountDownLatch(1);
        try (var executor = new InteractionExecutor("test-limits", 2, 10, 2)) {
            try {
                executor.submit("hot", () -> {
                    started.countDown();
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                executor.submit("hot", drained::countDown);
                assertThrows(RejectedExecutionException.class, () -> executor.submit("hot", () -> {}));
                executor.submit("other", other::countDown);
                assertTrue(other.await(5, TimeUnit.SECONDS));
                assertEquals(1, executor.snapshot().rejected());
            } finally { release.countDown(); }
            assertTrue(drained.await(5, TimeUnit.SECONDS));
            var resumed = new CountDownLatch(1);
            executor.submit("hot", resumed::countDown);
            assertTrue(resumed.await(5, TimeUnit.SECONDS));
        }
        var config = BunnyHub.create().snapshot();
        assertEquals(8, config.getCommandUserCapacity());
        assertEquals(2, config.getAutocompleteUserCapacity());
    }
}
