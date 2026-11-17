package org.bunnys.commands;

import net.dv8tion.jda.api.entities.MessageEmbed;
import org.bunnys.bunnynexus.info.StatusEmbeds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StatusCommandsTest {
    @Test void pingShowsBothLatenciesInCode() {
        var card = StatusEmbeds.ping(42, 118);
        assertEquals("**Gateway:** `42ms`\n**API:** `118ms`", body(card));
        assertSimple(card);
        assertEquals("**Gateway:** `n/a`\n**API:** `0ms`", body(StatusEmbeds.ping(-1, 0)), "not measured yet");
    }

    @Test void uptimeUsesUnixTimestamps() {
        var card = StatusEmbeds.uptime(1_760_000_000_123L);
        assertEquals("**Online since:** <t:1760000000:F>\n**Uptime:** started <t:1760000000:R>", body(card));
        assertSimple(card);
    }

    @Test void bothAreSimpleCommandsAnyoneCanUse() {
        for (var command : java.util.List.of(new Ping(null), new Uptime(null))) {
            assertTrue(command.getOptions().isEmpty() && command.getSubcommands().isEmpty(), command.getName());
            assertTrue(command.getUserPermissions().isEmpty(), command.getName());
            assertTrue(command.isDmEnabled() && command.isMentionEnabled(), command.getName());
            assertEquals("Information", command.getCategory());
            assertNotNull(command.buildCommandData().toData());
        }
        assertTrue(new Ping(null).matches("latency"));
    }

    /** The readings, without the footer line. */
    private static String body(org.bunnys.handler.cards.Card card) {
        String text = card.plainText();
        return text.substring(0, text.lastIndexOf("\n-# "));
    }

    /** One plain card: the readings and a footer, no heading, image or buttons. */
    private static void assertSimple(org.bunnys.handler.cards.Card card) {
        assertEquals(1, card.components().size());
        var container = (net.dv8tion.jda.api.components.container.Container) card.components().getFirst();
        assertNull(container.getAccentColorRaw());
        assertEquals(2, container.getComponents().size(), "the readings and the footer");
        assertTrue(card.plainText().endsWith("\n-# " + org.bunnys.utils.Embeds.FOOTER));
        assertFalse(card.plainText().contains("## "));
        assertTrue(card.message().isUsingComponentsV2());
    }
}
