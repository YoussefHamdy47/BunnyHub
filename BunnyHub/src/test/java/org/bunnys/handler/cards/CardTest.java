package org.bunnys.handler.cards;

import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.requests.restaction.interactions.MessageEditCallbackAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The handler's Components V2 card builder: order, merged fields, pings, accent, limits and sending. */
class CardTest {
    static Container card(Card card) {
        return (Container) card.components().stream().filter(c -> c instanceof Container).findFirst().orElseThrow();
    }

    @Test void partsKeepTheirOrderAndFieldsShareOneBlock() {
        var card = Card.builder()
                .header("## Help\nEverything", "https://cdn.example/avatar.png")
                .divider()
                .field("Study", "/timer start")
                .field("Info", "/ping")
                .section("### Game", Button.link("https://example.com", "Claim"))
                .image("https://cdn.example/art.png")
                .buttons(Button.secondary("help:back", "Back"))
                .footer("9 commands")
                .build();
        var parts = card(card).getComponents();
        assertTrue(parts.get(0) instanceof Section header && header.getAccessory() instanceof Thumbnail);
        assertTrue(parts.get(1) instanceof Separator);
        assertEquals("### Study\n/timer start\n\n### Info\n/ping", ((TextDisplay) parts.get(2)).getContent(), "consecutive fields share a block");
        assertTrue(parts.get(3) instanceof Section section && section.getAccessory() instanceof Button);
        assertTrue(parts.get(4) instanceof MediaGallery);
        assertTrue(parts.get(5) instanceof ActionRow);
        assertEquals("-# 9 commands", ((TextDisplay) parts.get(6)).getContent(), "the footer is always last");
        assertNull(card(card).getAccentColorRaw(), "plain by default");
    }

    @Test void onlyTheNamedRolesAndUsersArePingedAndEditsNeverPing() {
        var card = Card.builder().above("<@&1> <@2> 2 new free games").mentionRoles("1").mentionUsers("2").text("body").build();
        assertEquals("<@&1> <@2> 2 new free games", ((TextDisplay) card.components().getFirst()).getContent(), "above the card");
        MessageCreateData message = card.message();
        assertTrue(message.isUsingComponentsV2());
        assertEquals(Set.of("1"), message.getMentionedRoles());
        assertEquals(Set.of("2"), message.getMentionedUsers());
        assertTrue(message.getAllowedMentions().stream().noneMatch(t -> t == Message.MentionType.EVERYONE || t == Message.MentionType.HERE));
        assertTrue(card.edit().getAllowedMentions().isEmpty());
        assertTrue(card.edit().isUsingComponentsV2());
        assertTrue(Card.builder().text("x").build().message().getMentionedRoles().isEmpty(), "nothing pings unless named");
    }

    @Test void accentBlankPartsAndGalleries() {
        var card = Card.builder().accent(0xE74C3C).title("Error").text("  ").text(null).header("", "u")
                .gallery(Arrays.asList("https://a.png", "", null, "https://b.png")).image(" ").build();
        assertEquals(0xE74C3C, card(card).getAccentColorRaw());
        var parts = card(card).getComponents();
        assertEquals(2, parts.size(), "blank parts are skipped: " + parts);
        assertEquals(2, ((MediaGallery) parts.get(1)).getItems().size());
        assertEquals(1, Card.builder().above("only text").build().components().size(), "no empty card");
    }

    @Test void discordsLimitsAreCheckedWhenBuilding() {
        var tooLong = Card.builder().text("x".repeat(3000)).text("y".repeat(1001));
        assertFalse(tooLong.fits());
        var error = assertThrows(IllegalStateException.class, tooLong::build);
        assertTrue(error.getMessage().contains("4000"), error.getMessage());
        var many = Card.builder();
        for (int i = 0; i < 20; i++) many.section("game " + i, Button.link("https://example.com/" + i, "Claim"));
        assertFalse(many.fits(), "20 sections are 60 components");
        assertThrows(IllegalStateException.class, many::build);
        var rows = Card.builder();
        for (int i = 0; i < 6; i++) rows.buttons(Button.secondary("b" + i, "B"));
        assertThrows(IllegalStateException.class, rows::build);
        assertEquals(Card.MAX_TEXT - 4, Card.builder().text("abcd").remainingText());
        var fits = Card.builder().text("x".repeat(3990)).footer("ref");
        assertTrue(fits.fits());
        assertEquals(3996, fits.build().textLength());
    }

    @Test void countsMatchDiscordsRules() {
        var card = Card.builder().above("ping").section("a", Button.link("https://e.com", "Go")).buttons(Button.secondary("x", "X"), Button.secondary("y", "Y")).build();
        // above 1 + container 1 + section (1 + text 1 + accessory 1) + row (1 + 2 buttons)
        assertEquals(8, card.componentCount());
        assertEquals("ping".length() + "a".length(), card.textLength());
    }

    @SuppressWarnings("unchecked")
    @Test void editingAnOldEmbedMessageFallsBackToAPrivateReply() {
        var event = mock(ButtonInteractionEvent.class);
        var editAction = mock(MessageEditCallbackAction.class);
        var replyAction = mock(ReplyCallbackAction.class, RETURNS_SELF);
        when(event.isAcknowledged()).thenReturn(false);
        when(event.editMessage(any(net.dv8tion.jda.api.utils.messages.MessageEditData.class))).thenReturn(editAction);
        when(event.reply(any(MessageCreateData.class))).thenReturn(replyAction);
        var card = Card.builder().text("hello").build();
        Cards.edit(event, card);
        ArgumentCaptor<Consumer<Throwable>> failure = ArgumentCaptor.forClass(Consumer.class);
        verify(editAction).queue(any(), failure.capture());
        verify(event, never()).reply(any(MessageCreateData.class));
        failure.getValue().accept(new IllegalStateException("cannot convert an embed message"));
        verify(event).reply(any(MessageCreateData.class));
        verify(replyAction).setEphemeral(true);
    }

    @Test void componentsAreReadOnly() {
        List<MessageTopLevelComponent> components = Card.builder().text("x").build().components();
        assertThrows(UnsupportedOperationException.class, () -> components.add(TextDisplay.of("y")));
    }
}
