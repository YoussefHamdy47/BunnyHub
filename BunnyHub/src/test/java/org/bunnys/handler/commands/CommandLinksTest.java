package org.bunnys.handler.commands;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CommandLinksTest {
    final Map<String, String> ids = Map.of("free-games", "1554118765174853693", "timer", "1514618184400113699");

    @Test void ourCommandsInCodeBecomeLinks() {
        assertEquals("Run </free-games setup:1554118765174853693> again.", CommandLinks.render("Run `/free-games setup` again.", ids));
        assertEquals("</timer gpa:1514618184400113699> and </free-games dms:1554118765174853693>",
                CommandLinks.render("`/timer gpa` and `/free-games dms`", ids));
    }

    @Test void otherCodeIsLeftAlone() {
        assertEquals("Run `/bump` with Disboard.", CommandLinks.render("Run `/bump` with Disboard.", ids), "another bot's command");
        assertEquals("Use `/timer register <semester>`.", CommandLinks.render("Use `/timer register <semester>`.", ids), "syntax, not a path");
        assertEquals("Add `launcher:` here.", CommandLinks.render("Add `launcher:` here.", ids));
        assertNull(CommandLinks.render(null, ids));
    }

    @Test void bracesAlwaysMeanACommandAndFallBackToCode() {
        assertEquals("Open </free-games setup:1554118765174853693>.", CommandLinks.render("Open {/free-games setup}.", ids));
        assertEquals("Try `/deals`.", CommandLinks.render("Try {/deals}.", ids), "plain code until the command is deployed");
        assertEquals("Keep {this} and {/Not A Command}.", CommandLinks.render("Keep {this} and {/Not A Command}.", ids));
    }

    @Test void beforeTheFirstDeployEverythingStaysCode() {
        assertEquals("Run `/free-games setup`.", CommandLinks.render("Run `/free-games setup`.", Map.of()));
        assertEquals("`/help`", CommandLinks.of("help", Map.of()));
    }
}
