package org.bunnys.handler;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.bunnys.commands.Avatar;
import org.bunnys.commands.Timer;
import org.bunnys.events.InteractionListener;
import org.bunnys.handler.commands.*;
import org.bunnys.handler.commands.context.*;
import org.bunnys.handler.router.ComponentCooldowns;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NewHandlerTest {
    private static BunnyCommand command(String name) {
        var command = new BunnyCommand(null) {};
        command.setName(name); command.setDescription("Test command");
        return command;
    }
    private static BunnySubcommand sub(String name) {
        var sub = new BunnySubcommand() {};
        sub.setName(name); sub.setDescription("Test action");
        return sub;
    }
    private static CommandContext context() {
        var context = mock(CommandContext.class, CALLS_REAL_METHODS);
        var user = mock(User.class);
        when(user.getId()).thenReturn(UUID.randomUUID().toString());
        when(context.getUser()).thenReturn(user);
        return context;
    }

    @Test void aliasesResolveWithoutDuplicatingCommandsAndCanonicalNamesWin() {
        var registry = new CommandRegistry(null, List.of(), List.of());
        var avatar = new Avatar(null);
        registry.registerCommand(avatar);
        assertSame(avatar, registry.resolveCommand("PFP"));
        assertEquals(2, registry.getCommandCount());
        var canonical = command("pfp");
        registry.registerCommand(canonical);
        assertSame(canonical, registry.resolveCommand("pfp"));
        assertThrows(UnsupportedOperationException.class, () -> registry.getCommands().clear());
    }

    @Test void groupsSerializeAndHaveIndependentCooldownPaths() {
        var command = command("settings");
        var one = sub("set"); one.addAliases("change"); one.setCooldown(60);
        var two = sub("set"); two.setCooldown(60);
        command.addSubcommandGroup(new BunnySubcommandGroup("first", "First").addSubcommand(one));
        command.addSubcommandGroup(new BunnySubcommandGroup("second", "Second").addSubcommand(two));
        assertSame(one, command.resolve("first", "change"));
        assertNotNull(command.buildCommandData().toData());
        var registry = new CommandRegistry(null, List.of(), List.of());
        var ctx = context();
        assertNull(CommandGate.check(ctx, registry, command, one));
        assertNull(CommandGate.check(ctx, registry, command, two));
        assertNotNull(CommandGate.check(ctx, registry, command, one));
        CommandGate.release(ctx, command, one);
        assertNull(CommandGate.check(ctx, registry, command, one));
    }

    @Test void commandCooldownClaimIsAtomic() throws Exception {
        var command = command("atomic"); command.setCooldown(60);
        var ctx = context();
        var registry = new CommandRegistry(null, List.of(), List.of());
        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var jobs = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 50; i++) jobs.add(pool.submit(() -> {
                start.await();
                if (CommandGate.check(ctx, registry, command, null) == null) accepted.incrementAndGet();
                return null;
            }));
            start.countDown();
            for (var job : jobs) job.get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, accepted.get());
    }

    @Test void permissionsRespectChannelOverridesAndTestCommandsCannotRunElsewhere() {
        var ctx = context();
        var member = mock(Member.class);
        var channel = mock(TextChannel.class);
        var guild = mock(Guild.class);
        when(ctx.isFromGuild()).thenReturn(true);
        when(ctx.getGuild()).thenReturn(guild);
        when(ctx.getMember()).thenReturn(member);
        when(ctx.getChannel()).thenReturn(channel);
        var command = command("guarded");
        command.addUserPermissions(Permission.MESSAGE_MANAGE);
        when(member.hasPermission(Permission.MESSAGE_MANAGE)).thenReturn(true);
        var registry = new CommandRegistry(null, List.of(), List.of("allowed"));
        assertNotNull(CommandGate.check(ctx, registry, command, null));
        when(member.hasPermission(channel, Permission.MESSAGE_MANAGE)).thenReturn(true);
        assertNull(CommandGate.check(ctx, registry, command, null));
        command.setTestOnly(true);
        when(guild.getId()).thenReturn("other");
        assertNotNull(CommandGate.check(ctx, registry, command, null));
        when(guild.getId()).thenReturn("allowed");
        assertNull(CommandGate.check(ctx, registry, command, null));
    }

    @Test void mentionArgumentsPreserveQuotedTextAndEnforceDeclaredOptions() {
        var options = List.of(new OptionData(OptionType.STRING, "name", "Name", true),
                new OptionData(OptionType.INTEGER, "size", "Size").addChoice("2048", 2048),
                new OptionData(OptionType.BOOLEAN, "both", "Both"));
        var ctx = new MentionContext(mock(MessageReceivedEvent.class), options, "name:\"Course A: retake\" size:2048 both:yes");
        assertEquals("Course A: retake", ctx.getString("name"));
        assertEquals(2048, ctx.getInt("size"));
        assertTrue(ctx.getBool("both"));
        assertNull(ctx.validationError(options));
        var invalid = new MentionContext(mock(MessageReceivedEvent.class), options, "name:valid size:33");
        assertNotNull(invalid.validationError(options));
        assertNotNull(new MentionContext(mock(MessageReceivedEvent.class), options, "").validationError(options));
    }

    @Test void timerWaitsForAcknowledgementBeforeSchedulingAndKeepsGpaPrivate() {
        var hub = mock(BunnyHub.class);
        var registry = new CommandRegistry(hub, List.of(), List.of());
        registry.registerCommand(new Timer(null));
        when(hub.getCommandRegistry()).thenReturn(registry);
        var event = mock(SlashCommandInteractionEvent.class);
        var user = mock(User.class);
        when(event.getUser()).thenReturn(user);
        when(user.getId()).thenReturn("123");
        when(event.getName()).thenReturn("timer");
        when(event.getSubcommandName()).thenReturn("gpa");
        var action = mock(ReplyCallbackAction.class);
        when(event.deferReply(true)).thenReturn(action);
        new InteractionListener(hub).onSlashCommandInteraction(event);
        verify(hub, never()).executeForUser(anyString(), any());
        var success = org.mockito.ArgumentCaptor.forClass(Consumer.class);
        verify(action).queue(success.capture(), any());
        success.getValue().accept(null);
        verify(hub).executeForUser(eq("123"), any());
    }

    @Test void deferIsIdempotentAndAcknowledgedInteractionsCannotOpenModals() {
        var event = mock(SlashCommandInteractionEvent.class);
        var action = mock(ReplyCallbackAction.class);
        when(event.deferReply()).thenReturn(action);
        when(action.setEphemeral(true)).thenReturn(action);
        var context = new SlashContext(event);
        context.defer(true); context.defer(false);
        verify(event, times(1)).deferReply();
        when(event.isAcknowledged()).thenReturn(true);
        assertFalse(context.replyModal(null));
    }

    @Test void componentCooldownsAreIndependentAndCanReleaseRejectedWork() {
        var user = UUID.randomUUID().toString();
        var reservation = ComponentCooldowns.reserve("a", user, 10000);
        assertEquals(0, reservation.remainingMillis());
        assertTrue(ComponentCooldowns.claim("a", user, 10000) > 0);
        assertEquals(0, ComponentCooldowns.claim("b", user, 10000));
        reservation.release();
        assertEquals(0, ComponentCooldowns.claim("a", user, 10000));
    }

    @Test void boundedPoolRejectsOverflowAndShutdownRejectsNewWork() throws Exception {
        var executor = new InteractionExecutor(1, 1);
        var running = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            executor.submit("one", () -> {
                running.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(running.await(5, TimeUnit.SECONDS));
            executor.submit("two", () -> {});
            assertThrows(RejectedExecutionException.class, () -> executor.submit("three", () -> {}));
        } finally { release.countDown(); executor.close(); }
        assertThrows(RejectedExecutionException.class, () -> executor.submit("four", () -> {}));
    }
}
