package org.bunnys.handler;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.api.utils.ImageProxy;
import net.dv8tion.jda.api.utils.ImageFormat;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bunnys.commands.Avatar;
import org.bunnys.commands.Timer;
import org.bunnys.commands.info.Info;
import org.bunnys.handler.commands.CommandRegistry;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.handler.commands.context.UserContext;
import org.bunnys.events.InteractionListener;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserContextCommandTest {
    @Test void userMenuHasUserTypeAndNoSlashOptionsAndRegistersAlongsideSlash() {
        var avatar = new Avatar(null);
        var menu = avatar.buildUserContextCommandData().toData();
        assertEquals(2, menu.getInt("type"));
        assertEquals("Avatar", menu.getString("name"));
        assertTrue(!menu.hasKey("options") || menu.getArray("options").isEmpty());
        assertEquals(2, avatar.buildCommandDefinitions().size());
        var registry = new CommandRegistry(null, List.of(), List.of());
        registry.registerCommand(avatar);
        assertSame(avatar, registry.resolveUserContext("Avatar"));
        assertSame(avatar, registry.resolveCommand("av"));
        assertNull(registry.resolveUserContext("av"));
        assertEquals(2, registry.clearCommands());
        assertNull(registry.resolveUserContext("Avatar"));
    }

    @Test void userMenuSeparatesSelectedUserFromCaller() {
        var event = mock(UserContextInteractionEvent.class);
        var caller = mock(User.class);
        var target = mock(User.class);
        var targetMember = mock(Member.class);
        when(target.getId()).thenReturn("123");
        when(event.getUser()).thenReturn(caller);
        when(event.getTarget()).thenReturn(target);
        when(event.getTargetMember()).thenReturn(targetMember);
        var context = new UserContext(event);
        assertSame(caller, context.getUser());
        assertSame(target, context.getUserOptionOrSelf("user"));
        assertSame(targetMember, context.getMemberOption("user"));
        assertEquals("123", context.getString("user"));
        assertTrue(context.isEphemeralCapable());
        assertFalse(context.isSlash());
    }

    @Test void menuAcknowledgesPrivatelyBeforeSchedulingUsingCallerForTheCooldownAndQueue() {
        var hub = mock(BunnyHub.class);
        var registry = new CommandRegistry(hub, List.of(), List.of());
        AtomicReference<CommandContext> received = new AtomicReference<>();
        var avatar = new Avatar(hub) {
            @Override public void execute(BunnyHub client, CommandContext context) { received.set(context); }
        };
        registry.registerCommand(avatar);
        when(hub.getCommandRegistry()).thenReturn(registry);
        var event = mock(UserContextInteractionEvent.class);
        var caller = mock(User.class);
        when(caller.getId()).thenReturn("menu-caller");
        when(event.getUser()).thenReturn(caller);
        when(event.getName()).thenReturn("Avatar");
        var acknowledgement = mock(ReplyCallbackAction.class);
        when(event.deferReply(true)).thenReturn(acknowledgement);
        new InteractionListener(hub).onUserContextInteraction(event);
        verify(hub, never()).executeForUser(anyString(), any());
        var callback = org.mockito.ArgumentCaptor.<Consumer<net.dv8tion.jda.api.interactions.InteractionHook>>captor();
        verify(acknowledgement).queue(callback.capture(), any());
        callback.getValue().accept(null);
        var work = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(hub).executeForUser(eq("menu-caller"), work.capture());
        work.getValue().run();
        assertInstanceOf(UserContext.class, received.get());
    }

    @Test void userMenuShowsBothSelectedUsersAvatarsWithServerFirst() {
        var event = mock(UserContextInteractionEvent.class);
        var target = mock(User.class);
        when(target.getId()).thenReturn("123");
        when(target.getEffectiveAvatar()).thenReturn(new ImageProxy("https://cdn.discordapp.com/global.gif"));
        when(target.getEffectiveAvatar(ImageFormat.PNG)).thenReturn(new ImageProxy("https://cdn.discordapp.com/global.png"));
        var member = mock(Member.class);
        when(member.getEffectiveName()).thenReturn("Selected User");
        when(member.getAvatar()).thenReturn(new ImageProxy("https://cdn.discordapp.com/server.gif"));
        when(member.getAvatar(ImageFormat.PNG)).thenReturn(new ImageProxy("https://cdn.discordapp.com/server.png"));
        when(event.getTarget()).thenReturn(target);
        when(event.getTargetMember()).thenReturn(member);
        var context = spy(new UserContext(event));
        context.markDeferred(true);
        doNothing().when(context).replyMessage(any());
        new Avatar(null).execute(null, context);
        var message = org.mockito.ArgumentCaptor.forClass(MessageCreateData.class);
        verify(context).replyMessage(message.capture());
        assertEquals(2, message.getValue().getEmbeds().size());
        assertTrue(message.getValue().getEmbeds().getFirst().getTitle().contains("Selected User"));
        assertTrue(message.getValue().getEmbeds().getFirst().getTitle().contains("Server avatar"));
        assertTrue(message.getValue().getEmbeds().get(1).getImage().getUrl().contains("global.gif"));
    }

    @Test void allSubcommandsAreNamedDomainClassesAndKeepTheirExistingNames() {
        var timer = new Timer(null);
        assertEquals(Set.of("stats", "register", "gpa", "add-subject", "remove-subject", "update-subject",
                "start", "switch", "end-session", "end_semester"), timer.getSubcommands().keySet());
        for (var command : List.of(timer, new Info(null))) {
            for (var sub : command.getSubcommands().values()) {
                assertFalse(sub.getClass().isAnonymousClass());
                assertTrue(sub.getClass().getPackageName().startsWith("org.bunnys.bunnynexus.commands."));
            }
            assertNotNull(command.buildCommandData().toData());
        }
    }
}
