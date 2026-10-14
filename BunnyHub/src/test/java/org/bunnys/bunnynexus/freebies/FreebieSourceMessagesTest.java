package org.bunnys.bunnynexus.freebies;

import org.junit.jupiter.api.Test;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.EmbedBuilder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FreebieSourceMessagesTest {
    @Test void epicAttributionRegionReferencesAndCheckoutSuppression() throws Exception {
        var offer = FreebieOffer.read(FreebieSourceStore.fresh(EpicIntakeTest.candidate(), EpicIntakeTest.NOW));
        String ref = FreebieMessages.reference(offer.id() + "|100000000000000001");
        try (var alert = FreebieMessages.alert(offer, Optional.of("200000000000000001"), ref)) {
            var embed = alert.getEmbeds().getFirst();
            assertEquals("Powered by Epic Games • ref " + ref, embed.getFooter().getText());
            assertTrue(embed.getFields().stream().anyMatch(f -> "REGION".equals(f.getName()) && f.getValue().contains("US")));
            assertTrue(FreebieMessages.isAlertFooter(embed, ref)); assertFalse(FreebieMessages.isAlertFooter(embed, ref + "a"));
            String json = alert.toData().toString();
            assertFalse(json.contains("purchase?")); assertFalse(json.contains("offers=1-"));
            assertEquals("<@&200000000000000001>", alert.getContent());
            assertTrue(json.contains("200000000000000001")); assertFalse(json.contains("\"everyone\""));
        }
        try (var review = FreebieMessages.review(offer, 1, 1, Set.of("300000000000000001"))) {
            assertTrue(review.getEmbeds().get(1).getFields().stream().anyMatch(f -> "SOURCE / REGION".equals(f.getName()) && f.getValue().contains("Epic Games")));
            assertTrue(review.toData().toString().contains("Approve"));
        }
    }
    @Test void historicalFootersRemainExactAndRecoverable() {
        for (String prefix : List.of("Source: GamerPower • ref ", "Powered by GamerPower • ref ", "Powered by Epic Games • ref ")) {
            Message message = mock(Message.class);
            when(message.getEmbeds()).thenReturn(List.of(new EmbedBuilder().setDescription("old card").setFooter(prefix + "abc123").build()));
            assertTrue(FreebieMessages.carriesReference(message, "abc123"));
            assertFalse(FreebieMessages.carriesReference(message, "abc12"));
        }
    }
    @Test void gamerPowerEpicLauncherNeverAcquiresEpicAttributionOrCheckout() {
        var offer = FreebieOffer.read(FreebieOffer.newDocument(FreebieRepositoryIT.candidate("9", "PC, Epic Games Store"), EpicIntakeTest.NOW));
        try (var alert = FreebieMessages.alert(offer, Optional.empty(), "saved-reference")) {
            assertEquals("Powered by GamerPower • ref saved-reference", alert.getEmbeds().getFirst().getFooter().getText());
            assertFalse(alert.toData().toString().contains("purchase?"));
        }
    }
}
