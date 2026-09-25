package org.bunnys.handler;

import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import org.bunnys.events.InteractionListener;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class AutocompleteIsolationTest {
    @Test void autocompleteDoesNotEnterTheMutationQueue() {
        var hub = mock(BunnyHub.class);
        var event = mock(CommandAutoCompleteInteractionEvent.class);
        var user = mock(User.class);
        when(event.getUser()).thenReturn(user);
        when(user.getId()).thenReturn("123");
        new InteractionListener(hub).onCommandAutoCompleteInteraction(event);
        verify(hub).executeAutocomplete(eq("123"), any());
        verify(hub, never()).executeForUser(anyString(), any());
    }
}
