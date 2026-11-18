package org.bunnys.bunnynexus.timers;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.bunnys.bunnynexus.timers.buttons.SessionMenuManager;
import org.bunnys.bunnynexus.timers.buttons.SessionMenuManager.SessionState;
import org.bunnys.database.models.timers.Subject;
import org.bunnys.database.models.timers.TimerData;
import org.bunnys.database.models.user.BunnyUser;
import org.bunnys.handler.cards.Card;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TimerCardsTest {
    private static final String USER = "123";

    private static ActionRow row(SessionState state) { return ActionRow.of(SessionMenuManager.buildButtons(USER, state)); }

    private static Message posted(Card card) {
        var message = mock(Message.class);
        when(message.isUsingComponentsV2()).thenReturn(true);
        when(message.getComponentTree()).thenReturn(MessageComponentTree.of(card.components()));
        return message;
    }

    private static Card menu(String objective) {
        var embed = new EmbedBuilder().setTitle("Hello | CS101").setDescription(objective == null ? null : TimerCards.OBJECTIVE + objective)
                .addField("Record", "✦ Focus: `1h`\n​", false).setFooter("Session timer is live").build();
        return TimerCards.card(embed, row(SessionState.RUNNING));
    }

    private static List<Button> buttons(MessageEditData edit) {
        return MessageComponentTree.of(edit.getComponents()).findAll(Button.class);
    }

    private static String text(MessageEditData edit) {
        return edit.toData().toString();
    }

    @Test void swappingTheButtonsKeepsTheCardsText() {
        var edit = TimerCards.withRow(posted(menu("Finish chapter 3")), row(SessionState.PAUSED));
        assertTrue(edit.isUsingComponentsV2());
        assertTrue(text(edit).contains("Finish chapter 3") && text(edit).contains("Session timer is live"));
        assertTrue(buttons(edit).stream().anyMatch(b -> b.getCustomId().startsWith("session:resume:")));
        assertEquals(4, buttons(edit).size(), "the old row is replaced, not added to");
    }

    @Test void closingACardDisablesEveryButtonAndKeepsItsText() {
        var edit = TimerCards.disabled(posted(menu("Finish chapter 3")));
        assertTrue(buttons(edit).stream().allMatch(Button::isDisabled));
        assertTrue(text(edit).contains("Finish chapter 3"));
    }

    @Test void aRefreshKeepsTheObjectiveOfACardOrAnEmbed() {
        assertEquals(TimerCards.OBJECTIVE + "Finish chapter 3", TimerCards.current(posted(menu("Finish chapter 3"))).getDescription());
        assertNull(TimerCards.current(posted(menu(null))).getDescription());
        var embed = new EmbedBuilder().setDescription("from before cards").build();
        var old = mock(Message.class);
        when(old.getEmbeds()).thenReturn(List.of(embed));
        assertSame(embed, TimerCards.current(old));
    }

    @SuppressWarnings("unchecked")
    @Test void aMenuFromBeforeCardsStaysAnEmbed() {
        var old = mock(Message.class);
        var hook = mock(InteractionHook.class);
        var edit = mock(WebhookMessageEditAction.class, RETURNS_SELF);
        when(hook.editOriginalEmbeds(any(net.dv8tion.jda.api.entities.MessageEmbed.class))).thenReturn(edit);
        TimerCards.update(hook, old, new EmbedBuilder().setDescription("x").build(), List.of(row(SessionState.RUNNING)), null);
        verify(hook, never()).editOriginal(any(MessageEditData.class));
        verify(edit).setComponents(anyCollection());
    }

    @Test void pingsNotifyOnlyTheOwnerFromAboveTheCard() {
        var message = TimerCards.ping(USER, new EmbedBuilder().setTitle("Session Concluded").setDescription("<@999> recap").build());
        assertEquals(java.util.Set.of(USER), message.getMentionedUsers());
        assertTrue(message.getAllowedMentions().isEmpty());
        assertTrue(message.getContent().isEmpty(), "a card message has no content");
    }

    @Test void aFullStatsScreenFitsOneCardWithEverySection() {
        var timer = new TimerData();
        var subjects = new ArrayList<Subject>();
        for (int i = 0; i < 14; i++) {
            var subject = new Subject();
            subject.setSubjectCode("C".repeat(22) + (10 + i));
            subject.setSubjectName("N".repeat(70));
            subject.setCreditHours(4);
            subjects.add(subject);
        }
        timer.getCurrentSemester().setSemesterName("Fall 2026");
        timer.getCurrentSemester().setSemesterSubjects(subjects);
        var user = mock(User.class);
        when(user.getEffectiveName()).thenReturn("Student");
        var embed = new TimerEmbeds(timer, new BunnyUser(), user).statistics();
        var card = TimerCards.card(embed).pinging(USER);
        assertTrue(card.textLength() <= Card.MAX_TEXT, "text: " + card.textLength());
        assertTrue(card.plainText().contains("Progression & Status"), "the last section survives");
        assertFalse(card.plainText().contains("​"), "embed spacers are gone");
    }

    @Test void anOversizedEmbedKeepsTheFieldsThatFit() {
        var embed = new EmbedBuilder().setTitle("Big");
        for (int i = 0; i < 5; i++) embed.addField("Field " + i, "y".repeat(1000), false);
        var card = Card.fromEmbed(embed.build());
        assertTrue(card.textLength() <= Card.MAX_TEXT);
        assertTrue(card.plainText().contains("Field 2"), "whole fields are kept while they fit");
        assertFalse(card.plainText().contains("Field 4"));
    }
}
