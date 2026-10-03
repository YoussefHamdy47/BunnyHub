package org.bunnys.bunnynexus.help;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import org.bunnys.commands.Avatar;
import org.bunnys.commands.Help;
import org.bunnys.commands.Timer;
import org.bunnys.commands.freebies.Freebie;
import org.bunnys.commands.freebies.FreebieAdmin;
import org.bunnys.commands.info.Info;
import org.bunnys.handler.commands.BunnyCommand;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import org.bunnys.commands.freebies.FreeGames;

class HelpMenuTest {
    private final List<BunnyCommand> commands = List.of(new Avatar(null), new Timer(null), new Info(null),
            new Freebie(null), new FreebieAdmin(null), new Help(null), new FreeGames(null));
    private final HelpMenu.Bot bot = new HelpMenu.Bot("Bunny", null, Map.of("timer", "111", "avatar", "222"));
    private final HelpMenu.Viewer member = new HelpMenu.Viewer("42", false);
    private final HelpMenu.Viewer developer = new HelpMenu.Viewer("7", true);

    @Test void overviewHidesOwnerCommandsFromMembers() {
        String text = describe(HelpMenu.render(bot, commands, member, HelpMenu.OVERVIEW));
        assertTrue(text.contains("/freebie"));
        assertFalse(text.contains("freebie-admin"));
        assertFalse(HelpMenu.paths(commands, false).contains("freebie-admin"));
        assertTrue(describe(HelpMenu.render(bot, commands, developer, HelpMenu.OVERVIEW)).contains("freebie-admin"));
    }

    @Test void everyViewRendersWithinDiscordLimits() {
        List<String> views = new ArrayList<>(List.of(HelpMenu.OVERVIEW, "c=Study", "c=Free Games", "c=Information", "c=Owner"));
        for (String path : HelpMenu.paths(commands, true)) views.add(HelpMenu.detailKey(path));
        for (String view : views) {
            HelpMenu.Rendered rendered = HelpMenu.render(bot, commands, developer, view);
            assertTrue(rendered.embed().isSendable(), view);
            assertTrue(rendered.rows().size() <= 5, view);
            for (ActionRow row : rendered.rows())
                for (var component : row.getComponents()) {
                    if (component instanceof StringSelectMenu menu) {
                        assertTrue(menu.getOptions().size() <= 25, view);
                        assertTrue(menu.getCustomId().length() <= 100, view);
                        menu.getOptions().forEach(o -> assertTrue(o.getValue().length() <= 100, view));
                    } else if (component instanceof Button button) {
                        assertTrue(button.getCustomId().length() <= 100, view);
                    }
                }
        }
    }

    @Test void detailUsesClickableMentionsAndOwnerScopedControls() {
        var rendered = HelpMenu.render(bot, commands, member, "d=timer start");
        assertEquals("📘 /timer start", rendered.embed().getTitle());
        assertTrue(describe(rendered).contains("</timer start:111>"));
        var menu = rendered.rows().getFirst().getComponents().getFirst().asStringSelectMenu();
        assertEquals("help:cat:42", menu.getCustomId());
        var back = rendered.rows().get(2).getComponents().getFirst().asButton();
        assertEquals("help:go:42:d=timer:a", back.getCustomId());
        // Undeployed commands fall back to plain code rather than a broken mention.
        assertTrue(describe(HelpMenu.render(bot, commands, member, "d=info user")).contains("`/info user`"));
    }

    @Test void lookupToleratesSlashesAliasesAndPastedOptions() {
        assertEquals("📘 /timer start", HelpMenu.lookup(bot, commands, member, " /Timer  start subject:math ").embed().getTitle());
        assertEquals("📘 /avatar", HelpMenu.lookup(bot, commands, member, "pfp").embed().getTitle());
        assertEquals("📘 /timer", HelpMenu.lookup(bot, commands, member, "timer nonsense").embed().getTitle());
        assertTrue(HelpMenu.lookup(bot, commands, member, "study").embed().getTitle().contains("Study"));
        var missing = HelpMenu.lookup(bot, commands, member, "`nope`");
        assertTrue(missing.embed().getDescription().contains("No command matches `'nope'`"));
        // Owner commands stay hidden from members even when typed exactly.
        assertTrue(HelpMenu.lookup(bot, commands, member, "freebie-admin").embed().getDescription().contains("No command matches"));
    }

    @Test void staleViewsFallBackToTheOverview() {
        assertTrue(HelpMenu.render(bot, commands, member, "d=removed").embed().getTitle().endsWith(" Bunny Help"));
        assertTrue(HelpMenu.render(bot, commands, member, "c=Owner").embed().getTitle().endsWith(" Bunny Help"));
    }

    private static String describe(HelpMenu.Rendered rendered) {
        StringBuilder text = new StringBuilder(String.valueOf(rendered.embed().getDescription()));
        rendered.embed().getFields().forEach(f -> text.append('\n').append(f.getName()).append('\n').append(f.getValue()));
        return text.toString();
    }
}
