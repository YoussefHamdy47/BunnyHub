package org.bunnys.bunnynexus.help;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import org.bunnys.bunnynexus.help.HelpCatalog.Branch;
import org.bunnys.bunnynexus.help.HelpCatalog.Target;
import org.bunnys.handler.commands.BunnyCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.bunnys.bunnynexus.help.HelpCatalog.branches;
import static org.bunnys.bunnynexus.help.HelpMenu.*;
import static org.bunnys.bunnynexus.help.HelpText.*;

/**
 * The menus and buttons under every help view. The whole view lives in the component IDs
 * ({@code help:<action>:<owner>:<view>}), so no state is kept between clicks.
 */
final class HelpControls {
    private HelpControls() {}
    private static final int MAX_OPTION_TEXT = 100;

    static List<ActionRow> rows(HelpCatalog catalog, Viewer viewer, String view, String category, Target target) {
        String owner = viewer.userId();
        List<ActionRow> rows = new ArrayList<>();

        // 1. Where am I: overview or a category.
        StringSelectMenu.Builder categories = StringSelectMenu.create(PREFIX + ":cat:" + owner)
                .setPlaceholder("Choose a category");
        categories.addOptions(SelectOption.of("Overview", OVERVIEW)
                .withDescription("Every command at a glance").withEmoji(Emoji.fromUnicode("🏠"))
                .withDefault(OVERVIEW.equals(view)));
        for (Map.Entry<String, List<BunnyCommand>> entry : catalog.categories().entrySet()) {
            if (categories.getOptions().size() >= MAX_MENU_OPTIONS) break;
            int count = entry.getValue().size();
            categories.addOptions(SelectOption.of(entry.getKey(), categoryKey(entry.getKey()))
                    .withDescription(count + (count == 1 ? " command" : " commands"))
                    .withEmoji(Emoji.fromUnicode(emoji(entry.getKey())))
                    .withDefault(target == null && entry.getKey().equals(category)));
        }
        rows.add(ActionRow.of(categories.build()));

        // 2. Pick a command: the category's commands, plus the branches of the one on screen.
        List<BunnyCommand> scope = category == null ? catalog.all() : catalog.categories().get(category);
        String selected = target == null ? null : target.path();
        StringSelectMenu.Builder picker = StringSelectMenu.create(PREFIX + ":cmd:" + owner)
                .setPlaceholder("Pick a command for full usage");
        for (BunnyCommand command : scope) {
            if (picker.getOptions().size() >= MAX_MENU_OPTIONS) break;
            picker.addOptions(SelectOption.of("/" + command.getName(), detailKey(command.getName()))
                    .withDescription(shorten(command.getDescription(), MAX_OPTION_TEXT))
                    .withDefault(command.getName().equals(selected)));
            boolean expanded = target != null && target.command() == command;
            if (!expanded) continue;
            for (Branch branch : branches(command)) {
                if (picker.getOptions().size() >= MAX_MENU_OPTIONS) break;
                picker.addOptions(SelectOption.of("  ↳ /" + branch.path(), detailKey(branch.path()))
                        .withDescription(shorten(branch.subcommand().getDescription(), MAX_OPTION_TEXT))
                        .withDefault(branch.path().equals(selected)));
            }
        }
        if (picker.getOptions().isEmpty()) {
            picker.addOptions(SelectOption.of("No commands available", "none"));
            rows.add(ActionRow.of(picker.build().asDisabled()));
        } else {
            rows.add(ActionRow.of(picker.build()));
        }

        // 3. Navigation. Slots keep IDs unique when two buttons point at the same view.
        if (target != null) {
            String back = target.subcommand() != null
                    ? detailKey(target.command().getName())
                    : categoryKey(target.command().getCategory());
            String backLabel = target.subcommand() != null ? "Back to /" + target.command().getName() : "Back to " + target.command().getCategory();
            rows.add(ActionRow.of(
                    Button.secondary(PREFIX + ":go:" + owner + ":" + back + ":a", shorten(backLabel, 80))
                            .withEmoji(Emoji.fromUnicode("↩️")),
                    Button.secondary(PREFIX + ":go:" + owner + ":" + OVERVIEW + ":b", "Home")
                            .withEmoji(Emoji.fromUnicode("🏠"))));
        } else {
            List<String> pages = new ArrayList<>();
            pages.add(OVERVIEW);
            for (String name : catalog.categories().keySet()) pages.add(categoryKey(name));
            int index = Math.max(0, pages.indexOf(view));
            String previous = pages.get(Math.max(0, index - 1));
            String next = pages.get(Math.min(pages.size() - 1, index + 1));
            rows.add(ActionRow.of(
                    Button.secondary(PREFIX + ":go:" + owner + ":" + previous + ":a", "Previous")
                            .withEmoji(Emoji.fromUnicode("◀️")).withDisabled(index == 0),
                    Button.primary(PREFIX + ":go:" + owner + ":" + OVERVIEW + ":b", "Home")
                            .withEmoji(Emoji.fromUnicode("🏠")).withDisabled(index == 0),
                    Button.secondary(PREFIX + ":go:" + owner + ":" + next + ":c", "Next")
                            .withEmoji(Emoji.fromUnicode("▶️")).withDisabled(index == pages.size() - 1)));
        }
        return rows;
    }
}
