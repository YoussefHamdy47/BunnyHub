package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Message;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The ended card (footer unchanged, claim button gone, no mentions) and the ending-soon reminder. */
class FreebieLifecycleMessagesTest {
    static final String DELIVERY = "gamerpower:1|200000000000000000", ROLE = "300000000000000000";
    final String reference = FreebieMessages.reference(DELIVERY);
    final FreebieOffer offer = FreebieLifecycleTest.live();
    final net.dv8tion.jda.api.utils.messages.MessageCreateData alert = FreebieMessages.alert(offer, Optional.of(ROLE), reference);

    static List<Button> buttons(List<? extends net.dv8tion.jda.api.components.MessageTopLevelComponent> rows) {
        List<Button> buttons = new ArrayList<>();
        for (var row : rows) if (row instanceof ActionRow r) r.getComponents().forEach(c -> buttons.add((Button) c));
        return buttons;
    }

    @Test void endedCardKeepsTheFooterByteForByteAndRemovesTheClaimButton() {
        var card = alert.getEmbeds().getFirst();
        var edit = FreebieLifecycleMessages.ended(alert.getEmbeds(), alert.getComponents(), reference, false);
        var ended = edit.getEmbeds().getFirst();
        assertEquals(card.getFooter().getText(), ended.getFooter().getText());
        assertEquals("Powered by GamerPower • ref " + reference, ended.getFooter().getText());
        assertEquals("Ended: " + card.getTitle(), ended.getTitle());
        assertEquals(FreebieLifecycleMessages.ENDED_COLOUR, ended.getColorRaw());
        assertTrue(ended.getDescription().endsWith("**This giveaway has ended.**"), ended.getDescription());
        assertFalse(ended.getDescription().contains("Claim Game"));
        assertFalse(ended.getDescription().contains(offer.claimUrl().toString()), "no claim link left in the text");
        assertTrue(ended.getFields().stream().anyMatch(f -> f.getName().equals("CLAIM WINDOW") && "Ended".equals(f.getValue())));
        assertTrue(ended.getFields().stream().noneMatch(f -> f.getValue().contains("<t:")), "no countdown left");

        var row = buttons(alert.getComponents());
        assertEquals(3, row.size(), "claim centered between two spacers");
        assertTrue(row.get(1).getLabel().startsWith("Claim"));
        assertTrue(row.get(0).isDisabled() && row.get(2).isDisabled());
        assertNotEquals(row.get(0).getCustomId(), row.get(2).getCustomId());
        var before = row.stream().map(Button::getLabel).toList();
        var after = buttons(edit.getComponents()).stream().map(Button::getLabel).toList();
        assertTrue(before.stream().anyMatch(l -> l.startsWith("Claim")));
        assertEquals(List.of(), after, "only the claim button was there, so an ended card has none");
        assertTrue(before.stream().noneMatch(l -> l.equals("Offer details")), "credit is the footer, not a GamerPower link");
        // The original carried a role ping; the edit carries no content and allows no mention at all.
        assertEquals("<@&" + ROLE + ">", alert.getContent());
        assertEquals("", edit.getContent());
        assertTrue(edit.getAllowedMentions().isEmpty());
        assertTrue(edit.getMentionedRoles().isEmpty());
        assertTrue(edit.getMentionedUsers().isEmpty());
    }

    @Test void withdrawnWordingAndIdempotentRendering() {
        var once = FreebieLifecycleMessages.ended(alert.getEmbeds(), alert.getComponents(), reference, true);
        var twice = FreebieLifecycleMessages.ended(once.getEmbeds(), once.getComponents(), reference, true);
        var card = twice.getEmbeds().getFirst();
        assertTrue(card.getDescription().endsWith("**This giveaway was withdrawn.**"));
        assertEquals(1, card.getDescription().split("withdrawn", -1).length - 1, "the sentence is not repeated");
        assertFalse(card.getTitle().startsWith("Ended: Ended:"));
        assertEquals(once.getEmbeds().getFirst().getTitle(), card.getTitle());
        assertTrue(card.getFields().stream().anyMatch(f -> "Withdrawn".equals(f.getValue())));
    }

    @Test void aLegacyFooterIsKeptAsItWas() {
        var legacy = new EmbedBuilder(alert.getEmbeds().getFirst()).setFooter("Source: GamerPower • ref " + reference).build();
        var ended = FreebieLifecycleMessages.ended(List.of(legacy), alert.getComponents(), reference, false).getEmbeds().getFirst();
        assertEquals("Source: GamerPower • ref " + reference, ended.getFooter().getText());
        assertTrue(ended.getTitle().startsWith("Ended: "));
    }

    @Test void cardsPostedWithTheOldClaimHeadingStillLoseTheirLink() {
        var card = alert.getEmbeds().getFirst();
        var legacy = new EmbedBuilder(card).setDescription(card.getDescription().replace("**Claim Game**", "**Claim your game**")).build();
        var ended = FreebieLifecycleMessages.ended(List.of(legacy), alert.getComponents(), reference, false).getEmbeds().getFirst();
        assertFalse(ended.getDescription().contains("Claim your game"), ended.getDescription());
        assertFalse(ended.getDescription().contains(offer.claimUrl().toString()));
    }

    @Test void claimButtonIsTitleCasedBetweenWideSpacers() {
        var row = buttons(alert.getComponents());
        assertEquals(3, row.size());
        assertTrue(row.get(1).getLabel().matches("Claim (Epic|Steam) Offer|Claim Free Game"), row.get(1).getLabel());
        assertTrue(row.get(0).getLabel().length() >= 8 && row.get(0).getLabel().equals(row.get(2).getLabel()));
    }

    @Test void anEmbedWithoutTheReferenceIsNotRewritten() {
        var other = new EmbedBuilder().setTitle("Someone else's").setDescription("x").setFooter("other").build();
        var edit = FreebieLifecycleMessages.ended(List.of(other), List.of(), reference, false);
        assertEquals(other, edit.getEmbeds().getFirst());
    }

    @Test void recognisesAnAlreadyEndedCard() {
        var message = mock(Message.class);
        when(message.getEmbeds()).thenReturn(alert.getEmbeds());
        doReturn(alert.getComponents()).when(message).getComponents();
        assertFalse(FreebieLifecycleMessages.isEnded(message, reference));
        var edit = FreebieLifecycleMessages.ended(alert.getEmbeds(), alert.getComponents(), reference, false);
        when(message.getEmbeds()).thenReturn(edit.getEmbeds());
        doReturn(edit.getComponents()).when(message).getComponents();
        assertTrue(FreebieLifecycleMessages.isEnded(message, reference));
    }

    @Test void reminderPingsOnlyTheSavedRoleAndRepliesQuietly() {
        var now = FreebieLifecycleTest.END.minus(Duration.ofHours(24));
        var reminder = FreebieLifecycleMessages.reminder(offer, "200000000000000000", Optional.of(ROLE), now);
        assertEquals("<@&" + ROLE + ">", reminder.getContent());
        assertEquals(Set.of(ROLE), reminder.getMentionedRoles());
        assertTrue(reminder.getMentionedUsers().isEmpty());
        assertTrue(reminder.getAllowedMentions().isEmpty(), "never everyone/here, users or roles beyond the whitelist");
        assertFalse(reminder.isMentionRepliedUser());
        var embed = reminder.getEmbeds().getFirst();
        assertTrue(embed.getDescription().contains("Ends in about 24 hours"), embed.getDescription());
        assertEquals(FreebieLifecycleMessages.reminderReference(offer.id(), "200000000000000000"), embed.getFooter().getText());
        assertTrue(buttons(reminder.getComponents()).getFirst().getLabel().startsWith("Claim"));

        var quiet = FreebieLifecycleMessages.reminder(offer, "200000000000000000", Optional.empty(), now);
        assertTrue(quiet.getContent().isEmpty());
        assertTrue(quiet.getMentionedRoles().isEmpty());
    }

    @Test void reminderReferencesAreStablePerOfferAndChannel() {
        assertEquals(FreebieLifecycleMessages.reminderNonce("gamerpower:1", "2"), FreebieLifecycleMessages.reminderNonce("gamerpower:1", "2"));
        assertNotEquals(FreebieLifecycleMessages.reminderNonce("gamerpower:1", "2"), FreebieLifecycleMessages.reminderNonce("gamerpower:1", "3"));
        assertNotEquals(FreebieLifecycleMessages.reminderReference("gamerpower:1", "2"), "Powered by GamerPower • ref " + FreebieMessages.reference("gamerpower:1|2"));
    }
}
