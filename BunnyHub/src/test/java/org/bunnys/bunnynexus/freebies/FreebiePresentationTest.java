package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.entities.MessageEmbed;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class FreebiePresentationTest {
    @Test void recoveryRecognizesBothFooterGenerationsExactly() {
        var message = org.mockito.Mockito.mock(net.dv8tion.jda.api.entities.Message.class);
        for (String prefix : List.of("Source: GamerPower • ref ", "Powered by GamerPower • ref ")) {
            org.mockito.Mockito.when(message.getEmbeds()).thenReturn(List.of(new net.dv8tion.jda.api.EmbedBuilder()
                    .setDescription("Game").setFooter(prefix + "abc").build()));
            assertTrue(FreebieMessages.carriesReference(message, "abc"));
            assertFalse(FreebieMessages.carriesReference(message, "ab"));
            assertFalse(FreebieMessages.carriesReference(message, "different"));
        }
    }

    @Test void everyStoreGetsTheSameCardAndButton() {
        List<String> expected = List.of("Price", "Ends", "Launcher");
        for (var store : FreebieStore.values()) {
            var offer = FreebieUnitTest.offer("Example Giveaway", store);
            try (var message = FreebieMessages.alert(offer, Optional.empty(), "same-reference")) {
                var card = message.getEmbeds().getFirst();
                assertTrue(card.isSendable());
                assertEquals(expected, card.getFields().stream().map(MessageEmbed.Field::getName).toList(), store.name());
                assertEquals(store.label(), card.getFields().get(2).getValue());
                assertEquals("BunnyNexus • FREE GAME • " + store.label(), card.getAuthor().getName());
                assertFalse(card.getDescription().contains("Claim Game"), "the button claims; no duplicate link in the text");
                assertEquals("Powered by GamerPower • ref same-reference", card.getFooter().getText());
                assertTrue(message.getAllowedMentions().isEmpty());
                var row = message.getComponents().getFirst().asActionRow().getComponents();
                assertEquals(1, row.size(), "no spacer buttons");
                var button = row.getFirst().asButton();
                assertEquals("Claim Free Game", button.getLabel());
                assertEquals(offer.claimUrl().toString(), button.getUrl());
                if (store == FreebieStore.EPIC || store == FreebieStore.STEAM) {
                    assertTrue(button.getEmoji().getFormatted().contains(store == FreebieStore.EPIC
                            ? "824679104220626954" : "705062577817780315"));
                    assertNotNull(card.getAuthor().getIconUrl());
                } else assertTrue(card.getTitle().startsWith("🎁"));
            }
        }
    }

    @Test void endDateIsOnlyCalledEstimatedWhenItIs() {
        var end = Instant.parse("2027-01-01T12:00:00Z");
        var real = offer(true);
        var guessed = new FreebieOffer(real.id(), real.sourceId(), real.store(), real.platforms(), real.title(), real.description(),
                real.instructions(), real.worth(), real.image(), real.claimUrl(), real.pageUrl(), Optional.of(end), real.discoveredAt(),
                real.state(), real.reviewMessageId(), real.reviewChannelId(), real.decidedBy(), real.fanoutDone(), real.gone(),
                real.revision(), true);
        assertEquals("<t:" + end.getEpochSecond() + ":R>\n<t:" + end.getEpochSecond() + ":f>", FreebieCards.deadline(real));
        assertTrue(FreebieCards.deadline(guessed).endsWith("(estimated)"));
    }

    @Test void claimStepsAreOnlyOnTheDmAndReviewCards() {
        var rich = offer(true);
        assertTrue(FreebieMessages.alertEmbed(rich, "r").getFields().stream().noneMatch(f -> f.getName().equals("How to claim")));
        assertTrue(FreebieMessages.alertEmbed(rich, "r", true).getFields().stream().anyMatch(f -> f.getName().equals("How to claim")));
    }

    private static FreebieOffer offer(boolean rich) {
        return new FreebieOffer("gamerpower:9", "9", FreebieStore.EPIC, "*".repeat(2000), "🎮".repeat(300),
                "*".repeat(5000), rich ? "🎮 **Step** @everyone\n".repeat(500) : "", rich ? Optional.of("$19.99") : Optional.empty(),
                rich ? Optional.of(URI.create("https://example.com/game.jpg")) : Optional.empty(),
                URI.create("https://example.com/claim"), URI.create("https://example.com/details"),
                rich ? Optional.of(Instant.parse("2027-01-01T12:00:00Z")) : Optional.empty(), Instant.EPOCH,
                FreebieOffer.State.PENDING, Optional.empty(), Optional.empty(), Optional.empty(), false, false, 0);
    }

    @Test void richReviewFitsCombinedDiscordEmbedBudget() {
        try (var review = FreebieMessages.review(offer(true), 9999, 9999, Set.of())) {
            assertTrue(review.getEmbeds().stream().allMatch(MessageEmbed::isSendable));
            assertTrue(review.getEmbeds().stream().mapToInt(MessageEmbed::getLength).sum() <= 6000);
            var alert = review.getEmbeds().getFirst();
            assertEquals("https://example.com/game.jpg", alert.getImage().getUrl());
            assertNull(alert.getThumbnail(), "the game art is the full-width image, not a corner thumbnail");
            var instructions = alert.getFields().stream().filter(f -> f.getName().equals("How to claim")).findFirst().orElseThrow();
            assertTrue(instructions.getValue().contains("\\*\\*Step\\*\\*"));
            assertTrue(review.getAllowedMentions().isEmpty());
            assertTrue(review.getMentionedUsers().isEmpty());
        }
    }

    @Test void sparseOfferHasHonestFallbacksAndNoEmptyInstructions() {
        try (var alert = FreebieMessages.alert(offer(false), Optional.empty(), "stable")) {
            var embed = alert.getEmbeds().getFirst();
            assertTrue(embed.isSendable());
            assertNull(embed.getImage());
            assertNull(embed.getThumbnail());
            assertNull(embed.getTimestamp());
            assertEquals(3, embed.getFields().size());
            assertTrue(embed.getFields().getFirst().getValue().startsWith("**FREE**"));
            assertTrue(embed.getFields().get(1).getValue().contains("No end date listed"));
            assertEquals("Powered by GamerPower • ref stable", embed.getFooter().getText());
        }
    }

    @Test void approvalConfirmationStillCarriesAudienceAndOriginalAction() {
        try (var confirmation = FreebieMessages.confirm(offer(true), 42, 18)) {
            var embed = confirmation.getEmbeds().getFirst();
            assertTrue(embed.isSendable());
            assertEquals(List.of("42", "18"), embed.getFields().stream().map(MessageEmbed.Field::getValue).toList());
            assertTrue(embed.getDescription().contains("cannot be recalled"));
            assertEquals("freebie_review:confirm:gamerpower:9", confirmation.getComponents().getFirst()
                    .asActionRow().getComponents().getFirst().asButton().getCustomId());
            assertTrue(confirmation.getAllowedMentions().isEmpty());
        }
    }
}
