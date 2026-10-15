package org.bunnys.bunnynexus.help;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.bunnynexus.help.HelpCatalog.Branch;
import org.bunnys.bunnynexus.help.HelpCatalog.Target;
import org.bunnys.utils.Embeds;

import java.util.ArrayList;
import java.util.List;

import static org.bunnys.bunnynexus.help.HelpCatalog.branches;
import static org.bunnys.bunnynexus.help.HelpMenu.*;
import static org.bunnys.bunnynexus.help.HelpText.*;

/**
 * The help card for one command or subcommand: what it does, how to call it (slash, mention, shortcuts,
 * right-click), a copy-pasteable example and every option. A command with subcommands lists its branches
 * instead. Pure rendering, like {@link HelpMenu}, which owns the shared helpers used here.
 */
final class HelpDetail {
    private HelpDetail() {}

    static Rendered render(Bot bot, HelpCatalog catalog, Viewer viewer, Target target) {
        BunnyCommand command = target.command();
        BunnySubcommand sub = target.subcommand();
        String path = target.path();

        EmbedBuilder embed = base(bot).setTitle("/" + path);
        String description = sub != null ? sub.getDescription() : command.getDescription();

        StringBuilder intro = new StringBuilder(describe(description));
        String badges = badges(command, sub);
        if (!badges.isEmpty()) intro.append("\n\n").append(badges);
        embed.setDescription(intro.toString());

        if (sub == null && command.hasBranches()) branchFields(embed, bot, command);
        else usageFields(embed, bot, command, sub, path);

        StringBuilder footer = new StringBuilder(command.getCategory());
        if (sub != null) footer.append(" • Part of /").append(command.getName());
        Embeds.footer(embed, footer.toString());
        return new Rendered(embed.build(), HelpControls.rows(catalog, viewer, detailKey(path), command.getCategory(), target));
    }

    /** A runnable command or subcommand: how to call it, an example, then each option. */
    private static void usageFields(EmbedBuilder embed, Bot bot, BunnyCommand command, BunnySubcommand sub, String path) {
        List<OptionData> options = sub != null ? sub.getOptions() : command.getOptions();
        boolean mentionable = command.isMentionEnabled() && (sub == null || sub.isMentionEnabled());
        String args = syntax(options);

        StringBuilder usage = new StringBuilder();
        usage.append(reference(bot, path));
        if (!args.isEmpty()) usage.append("\n`/").append(path).append(args).append("`")
                .append("\n`<value>` is required, `[value]` is optional.");
        if (mentionable) usage.append("\nAlso works as `@").append(bot.name()).append(" ").append(path).append(args).append("`");

        List<String> aliases = sub != null ? sub.getAliases() : command.getAliases();
        if (mentionable && !aliases.isEmpty()) {
            String prefix = path.contains(" ") ? path.substring(0, path.lastIndexOf(' ') + 1) : "";
            List<String> shortcuts = new ArrayList<>();
            for (String alias : aliases) shortcuts.add("`@" + bot.name() + " " + prefix + alias + "`");
            usage.append("\nShortcuts: ").append(String.join(" ", shortcuts));
        }
        if (sub == null && command.getUserContextName() != null)
            usage.append("\nOr right-click a user → **Apps → ").append(command.getUserContextName()).append("**");
        if (sub == null && command.defaultSubcommand() != null && mentionable)
            usage.append("\n`@").append(bot.name()).append(" ").append(command.getName()).append("` alone runs **")
                    .append(command.defaultSubcommand().getName()).append("**.");
        field(embed, "How to use", usage.toString());

        String declared = sub != null ? sub.getExample() : command.getExample();
        if ((declared != null && !declared.isBlank()) || !options.isEmpty())
            field(embed, "Example", example(declared, path, options));

        if (options.isEmpty()) {
            field(embed, "Options", "None. Just run it.");
            return;
        }

        List<String> lines = new ArrayList<>();
        for (OptionData option : options) lines.add(optionLine(option));
        chunked(embed, "Options", lines);
    }

    /** A command with subcommands: one short line per branch, each with its own clickable usage. */
    private static void branchFields(EmbedBuilder embed, Bot bot, BunnyCommand command) {
        List<String> lines = new ArrayList<>();
        for (Branch branch : branches(command))
            lines.add(reference(bot, branch.path()) + " " + shorten(branch.subcommand().getDescription(), 80));
        StringBuilder block = new StringBuilder();
        boolean first = true;
        for (String line : lines) {
            if (block.length() + line.length() + 1 > MAX_FIELD_VALUE) {
                if (!field(embed, first ? "Commands" : "Commands (cont.)", block.toString())) return;
                first = false;
                block.setLength(0);
            }
            block.append(line).append("\n");
        }
        if (!block.isEmpty()) field(embed, first ? "Commands" : "Commands (cont.)", block.toString());
        field(embed, "More detail", "Pick one from the menu below for its options and an example.");
    }

    private static String optionLine(OptionData option) {
        StringBuilder line = new StringBuilder("`").append(option.getName()).append("` ")
                .append(option.isRequired() ? "**required**" : "optional")
                .append(" · ").append(typeHint(option));
        if (option.getDescription() != null && !option.getDescription().isBlank())
            line.append("\n").append(option.getDescription());
        List<Command.Choice> choices = option.getChoices();
        if (!choices.isEmpty()) {
            List<String> values = new ArrayList<>();
            // The value is what a mention must type; the name is what the slash picker shows.
            for (Command.Choice choice : choices)
                values.add(choice.getName().equals(choice.getAsString())
                        ? "`" + choice.getName() + "`"
                        : "`" + choice.getAsString() + "` (" + choice.getName() + ")");
            line.append("\nOne of: ").append(String.join(" ", values));
        }
        return line.toString();
    }

    /** Copy-pasteable: the declared example, or every required option filled with a sample. */
    private static String example(String declared, String path, List<OptionData> options) {
        String slash;
        if (declared != null && !declared.isBlank()) {
            slash = declared.trim();
        } else {
            StringBuilder generated = new StringBuilder("/").append(path);
            for (OptionData option : options)
                if (option.isRequired()) generated.append(" ").append(option.getName()).append(":").append(sample(option));
            slash = generated.toString();
        }
        return "`" + slash + "`";
    }
}
