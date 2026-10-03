package org.bunnys.bunnynexus.commands.timer;

import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import org.bunnys.handler.commands.context.SlashContext;
import org.bunnys.handler.utils.InteractionErrors;
import org.bunnys.utils.AppDesign;
import org.junit.jupiter.api.Test;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TimerSubcommandTest {
    static final class Probe extends TimerSubcommand {
        final Supplier<RuntimeException> failure;
        Probe(Supplier<RuntimeException> failure) { this.failure = failure; setName("probe"); setDescription("probe"); }
        @Override void run(SlashCommandInteractionEvent event, String userId) { throw failure.get(); }
    }

    final SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
    final InteractionHook hook = mock(InteractionHook.class);

    TimerSubcommandTest() {
        var user = mock(User.class);
        when(user.getId()).thenReturn("123");
        when(event.getUser()).thenReturn(user);
        when(event.getHook()).thenReturn(hook);
        doReturn(mock(WebhookMessageEditAction.class)).when(hook).editOriginal(anyString());
    }

    @Test void authoredFailuresAreShownPrivatelyAsErrorEmbeds() {
        var context = spy(new SlashContext(event));
        doNothing().when(context).replyTransient(any());
        new Probe(() -> new InteractionErrors.StateFailure("No active semester found.")).execute(null, context);
        verify(context).replyTransient(argThat(embed -> AppDesign.ColorCodes.ERROR_RED.equals(embed.getColor())
                && embed.getDescription().contains("No active semester found.")));
        verify(hook, never()).editOriginal(anyString());
    }

    @Test void unexpectedFailuresReachTheHandlerToBeLoggedInsteadOfBeingSwallowed() {
        var probe = new Probe(() -> new IllegalStateException("state should be: open"));
        assertThrows(IllegalStateException.class, () -> probe.execute(null, new SlashContext(event)));
        verify(hook, never()).editOriginal(anyString());
    }
}
