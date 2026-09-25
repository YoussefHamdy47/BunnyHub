package org.bunnys.handler;

import org.bunnys.commands.Avatar;
import org.bunnys.handler.commands.CommandRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class HandlerV2Test {
    @Test void eventCleanupPreservesExternalListeners() {
        var jda = org.mockito.Mockito.mock(net.dv8tion.jda.api.JDA.class);
        Object external = new Object();
        var own = org.mockito.Mockito.mock(org.bunnys.handler.events.BunnyEvent.class);
        org.mockito.Mockito.when(jda.getRegisteredListeners()).thenReturn(List.of(external, own));
        assertEquals(1, org.bunnys.handler.events.EventLoader.clearEvents(jda));
        org.mockito.Mockito.verify(jda).removeEventListener(own);
        org.mockito.Mockito.verify(jda, org.mockito.Mockito.never()).removeEventListener(external);
    }

    @Test void obsoleteSubcommandNeverDispatchesParentCommand() {
        var hub = org.mockito.Mockito.mock(BunnyHub.class);
        var registry = new CommandRegistry(hub, List.of(), List.of());
        registry.registerCommand(new org.bunnys.commands.Timer(null));
        org.mockito.Mockito.when(hub.getCommandRegistry()).thenReturn(registry);
        var event = org.mockito.Mockito.mock(net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent.class);
        var user = org.mockito.Mockito.mock(net.dv8tion.jda.api.entities.User.class);
        org.mockito.Mockito.when(event.getUser()).thenReturn(user);
        org.mockito.Mockito.when(event.getName()).thenReturn("timer");
        org.mockito.Mockito.when(event.getSubcommandName()).thenReturn("obsolete");
        var reply = org.mockito.Mockito.mock(net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction.class,
                org.mockito.Mockito.RETURNS_SELF);
        org.mockito.Mockito.when(event.reply(org.mockito.ArgumentMatchers.anyString())).thenReturn(reply);
        new org.bunnys.events.InteractionListener(hub).onSlashCommandInteraction(event);
        org.mockito.Mockito.verify(reply).setEphemeral(true);
        org.mockito.Mockito.verify(hub, org.mockito.Mockito.never()).executeForUser(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test void oldCancellationCannotRemoveANewerReservation() {
        var clock = new AtomicLong();
        var store = new CooldownStore("test.v2.ownership", 100, clock::get);
        var old = store.reserve("user", 10);
        clock.set(11_000_000);
        var current = store.reserve("user", 10);
        old.release();
        assertEquals(10, store.reserve("user", 10).remainingMillis());
        current.release();
        assertEquals(0, store.reserve("user", 10).remainingMillis());
    }

    @Test void rejectedReservationCannotCancelAcceptedWork() {
        var store = new CooldownStore("test.v2.rejected", 100, () -> 0);
        store.reserve("user", 100);
        store.reserve("user", 100).release();
        assertEquals(100, store.reserve("user", 100).remainingMillis());
        assertThrows(IllegalArgumentException.class, () -> store.reserve("long", CooldownStore.MAX_MILLIS + 1));
    }

    @Test void duplicateRoutesFailWithoutPublishingAliasesOrChangingExistingCommands() {
        var registry = new CommandRegistry(null, List.of(), List.of());
        var original = new Avatar(null);
        registry.registerCommand(original);
        var snapshot = registry.getCommands();
        var duplicate = new Avatar(null);
        duplicate.addAliases("unpublished");
        assertThrows(IllegalArgumentException.class, () -> registry.registerCommand(duplicate));
        assertSame(original, registry.resolveCommand("avatar"));
        assertNull(registry.resolveCommand("unpublished"));
        duplicate.setName("another");
        assertThrows(IllegalArgumentException.class, () -> registry.registerCommand(duplicate));
        assertNull(registry.resolveCommand("another"));
        registry.registerCommand(original);
        assertEquals(2, registry.clearCommands());
        assertTrue(registry.getCommands().isEmpty());
        assertSame(original, snapshot.get("avatar"));
    }

    @Test void builderMutationCannotChangeStartupSnapshot() {
        var builder = BunnyHub.create().setCommandPool(2, 3).addDeveloperIds("one");
        var snapshot = builder.snapshot();
        builder.setCommandPool(4, 5).addDeveloperIds("two").onShutdown("later", () -> {});
        assertEquals(2, snapshot.getCommandPoolSize());
        assertEquals(List.of("one"), snapshot.getDeveloperIds());
        assertTrue(snapshot.getShutdownActions().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getDeveloperIds().clear());
        assertThrows(IllegalArgumentException.class, () -> builder.setTokenKey(" "));
    }

    @Test void cleanupFailureDoesNotSkipLaterStages() {
        var completed = new AtomicInteger();
        var stages = List.of(new ShutdownAction("failed", () -> { throw new IllegalStateException("test"); }),
                new ShutdownAction("next", completed::incrementAndGet));
        stages.forEach(ShutdownAction::run);
        assertEquals(1, completed.get());
        assertEquals("2.0.0", BunnyHub.HANDLER_VERSION);
    }
}
