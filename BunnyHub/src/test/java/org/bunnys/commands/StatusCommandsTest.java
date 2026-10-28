package org.bunnys.commands;

import net.dv8tion.jda.api.entities.MessageEmbed;
import org.bunnys.bunnynexus.info.StatusEmbeds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StatusCommandsTest {
    @Test void pingShowsBothLatenciesInCode() {
        var embed = StatusEmbeds.ping(42, 118);
        assertEquals("**Gateway:** `42ms`\n**API:** `118ms`", embed.getDescription());
        assertSimple(embed);
        assertEquals("**Gateway:** `n/a`\n**API:** `0ms`", StatusEmbeds.ping(-1, 0).getDescription(), "not measured yet");
    }

    @Test void uptimeUsesUnixTimestamps() {
        var embed = StatusEmbeds.uptime(1_760_000_000_123L);
        assertEquals("**Online since:** <t:1760000000:F>\n**Uptime:** started <t:1760000000:R>", embed.getDescription());
        assertSimple(embed);
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

    /** Only a description, a footer and a timestamp: no title, fields, author, image or thumbnail. */
    private static void assertSimple(MessageEmbed embed) {
        assertNull(embed.getTitle());
        assertTrue(embed.getFields().isEmpty());
        assertNull(embed.getAuthor());
        assertNull(embed.getImage());
        assertNull(embed.getThumbnail());
        assertNotNull(embed.getFooter());
        assertNotNull(embed.getTimestamp());
        assertTrue(embed.isSendable());
    }
}
