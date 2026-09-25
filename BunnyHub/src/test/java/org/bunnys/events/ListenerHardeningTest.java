package org.bunnys.events;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.handler.ShutdownAction;
import org.bunnys.handler.commands.context.MentionContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ListenerHardeningTest {
    @Test void staleAutocompleteRequestsAreSkipped() {
        Instant now = Instant.parse("2026-09-22T12:00:00Z");
        assertFalse(InteractionListener.autocompleteExpired(OffsetDateTime.ofInstant(now.minusMillis(500), ZoneOffset.UTC), now));
        assertTrue(InteractionListener.autocompleteExpired(OffsetDateTime.ofInstant(now.minusSeconds(3), ZoneOffset.UTC), now));
        assertFalse(InteractionListener.autocompleteExpired(null, now));
    }

    @Test void mentionNoticesAreThrottledPerUser() {
        assertTrue(MessageListener.mayNotify("notice-user-1"));
        assertFalse(MessageListener.mayNotify("notice-user-1"));
        assertTrue(MessageListener.mayNotify("notice-user-2"));
    }

    @Test void mentionChoicesResolveToTheDeclaredValue() {
        var option = new OptionData(OptionType.STRING, "destination", "Where", true)
                .addChoice("Current Semester", "SEMESTER");
        var context = new MentionContext(mock(MessageReceivedEvent.class), List.of(option), "semester");
        assertNull(context.validationError(List.of(option)));
        assertEquals("SEMESTER", context.getString("destination"));

        var invalid = new MentionContext(mock(MessageReceivedEvent.class), List.of(option), "elsewhere");
        assertNotNull(invalid.validationError(List.of(option)));
    }

    @Test void shutdownErrorsDoNotSkipLaterStages() {
        var later = new AtomicBoolean();
        new ShutdownAction("broken", () -> { throw new NoClassDefFoundError("gone"); }).run();
        new ShutdownAction("later", () -> later.set(true)).run();
        assertTrue(later.get());
    }
}
