package org.bunnys.bunnynexus.help;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.bunnynexus.help.HelpCatalog.Branch;
import org.bunnys.bunnynexus.help.HelpCatalog.Target;
import org.bunnys.utils.AppDesign;
import org.bunnys.utils.Embeds;
import java.time.Instant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.bunnys.bunnynexus.help.HelpCatalog.branches;
import static org.bunnys.bunnynexus.help.HelpText.*;

/**
 * Renders the help menu: an overview, one page per category, and a detail card per command
 * or subcommand.
 *
 * <p>Pure rendering. Everything is derived from the registered {@link BunnyCommand} tree and
 * the same {@link OptionData} the slash commands deploy, so the printed syntax cannot drift
 * from what Discord accepts. Deployed command IDs turn every usage into a clickable
 * {@code </name:id>} mention; before the first deploy they fall back to plain code text.
 *
 * <h2>State</h2>
 * The whole view lives in the component IDs ({@code help:<action>:<owner>:<view>}), so the
 * menu needs no cache and survives restarts. Views are {@code o} (overview), {@code c=Name}
 * (category) and {@code d=path} (detail, e.g. {@code d=timer start}).
 *
 * <p>Only the person who opened a menu may drive it. Anyone else who clicks gets their own
 * private copy of the view they asked for, rather than a refusal.
 */
public final class HelpMenu {
    public static final String PREFIX = "help";
    public static final String OVERVIEW = "o";

    static final int MAX_MENU_OPTIONS = 25;

    private static final String TAGLINE = "Study timers, GPA tracking and free-game alerts for your server.";

    private HelpMenu() {}

    /** How the bot appears to the reader, plus deployed command IDs for clickable mentions. */
    public record Bot(String name, String avatarUrl, Map<String, String> commandIds) {
        public Bot {
            commandIds = commandIds == null ? Map.of() : commandIds;
        }
    }

    /** Who is reading. Developer-only and test-only commands are shown to developers alone. */
    public record Viewer(String userId, boolean developer) {}

    public record Rendered(MessageEmbed embed, List<ActionRow> rows) {}

    public static String categoryKey(String category) { return "c=" + category; }
    public static String detailKey(String path) { return "d=" + path; }

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    /** Renders a view key. Unknown or stale keys (a command since removed) fall back to the overview. */
    public static Rendered render(Bot bot, Collection<BunnyCommand> commands, Viewer viewer, String view) {
        HelpCatalog catalog = new HelpCatalog(commands, viewer.developer());

        if (view != null && view.startsWith("c=")) {
            String category = catalog.category(view.substring(2));
            if (category != null) return categoryPage(bot, catalog, viewer, category);
        } else if (view != null && view.startsWith("d=")) {
            Target target = catalog.resolve(view.substring(2));
            if (target != null) return detail(bot, catalog, viewer, target);
        }
        return overview(bot, catalog, viewer, null);
    }

    /**
     * {@code /help command:<typed>}. Tolerant of a leading slash, extra spaces, pasted options
     * and trailing words; falls back to the overview with a note when nothing matches.
     */
    public static Rendered lookup(Bot bot, Collection<BunnyCommand> commands, Viewer viewer, String typed) {
        HelpCatalog catalog = new HelpCatalog(commands, viewer.developer());
        if (typed == null || typed.isBlank()) return overview(bot, catalog, viewer, null);

        String cleaned = typed.trim().toLowerCase(Locale.ROOT);
        if (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        List<String> words = new ArrayList<>();
        for (String word : cleaned.split("\\s+")) {
            if (word.contains(":")) break; // pasted options, e.g. "timer start subject:math"
            if (!word.isEmpty()) words.add(word);
        }

        // Longest match first, so "timer start now" still lands on /timer start.
        for (int size = Math.min(words.size(), 3); size > 0; size--) {
            Target target = catalog.resolve(String.join(" ", words.subList(0, size)));
            if (target != null) return detail(bot, catalog, viewer, target);
        }

        // A category name is a reasonable thing to type too.
        String category = catalog.category(typed.trim());
        if (category != null) return categoryPage(bot, catalog, viewer, category);

        return overview(bot, catalog, viewer, "No command matches `" + sanitize(typed, 40)
                + "`. Here is everything instead.");
    }

    /** Every command path the viewer may look up, for {@code /help command:} autocomplete. */
    public static List<String> paths(Collection<BunnyCommand> commands, boolean developer) {
        HelpCatalog catalog = new HelpCatalog(commands, developer);
        List<String> paths = new ArrayList<>();
        for (BunnyCommand command : catalog.all()) {
            paths.add(command.getName());
            for (Branch branch : branches(command)) paths.add(branch.path());
        }
        return paths;
    }

    // ------------------------------------------------------------------
    // Overview
    // ------------------------------------------------------------------

    private static Rendered overview(Bot bot, HelpCatalog catalog, Viewer viewer, String notice) {
        EmbedBuilder embed = base(bot).setTitle(AppDesign.Emojis.VERIFY + " " + bot.name() + " Help");

        StringBuilder intro = new StringBuilder();
        if (notice != null) intro.append("⚠️ ").append(notice).append("\n\n");
        intro.append(TAGLINE).append("\n\n")
                .append("• Type `/` and pick a command, or click a blue command name below.\n")
                .append("• Commands marked 💬 also work as `@").append(bot.name()).append(" command`.\n")
                .append("• Use the menus below, or `/help command:<name>`, for full usage and examples.");
        embed.setDescription(intro.toString());

        if (catalog.isEmpty()) {
            field(embed, "Nothing here yet", "No commands are available to you right now.");
        } else {
            for (Map.Entry<String, List<BunnyCommand>> entry : catalog.categories().entrySet()) {
                StringBuilder lines = new StringBuilder();
                for (BunnyCommand command : entry.getValue())
                    lines.append(rootReference(bot, command)).append(" ")
                            .append(shorten(command.getDescription(), 70)).append("\n");
                field(embed, emoji(entry.getKey()) + " " + entry.getKey(), lines.toString());
            }
        }

        int count = catalog.all().size();
        Embeds.footer(embed, count + (count == 1 ? " command" : " commands") + " • Overview");
        return new Rendered(embed.build(), HelpControls.rows(catalog, viewer, OVERVIEW, null, null));
    }

    // ------------------------------------------------------------------
    // Category page
    // ------------------------------------------------------------------

    private static Rendered categoryPage(Bot bot, HelpCatalog catalog, Viewer viewer, String category) {
        List<BunnyCommand> inCategory = catalog.categories().get(category);
        EmbedBuilder embed = base(bot).setTitle(emoji(category) + " " + category);
        embed.setDescription("Pick a command from the menu below for its options and an example.\n"
                + "`<value>` is required, `[value]` is optional.");

        for (BunnyCommand command : inCategory) {
            StringBuilder body = new StringBuilder();
            body.append("*").append(describe(command.getDescription())).append("*\n");
            if (command.hasBranches()) {
                List<String> refs = new ArrayList<>();
                for (Branch branch : branches(command)) refs.add(reference(bot, branch.path()));
                body.append(String.join(" · ", refs));
            } else {
                body.append(reference(bot, command.getName()));
                String args = syntax(command.getOptions());
                if (!args.isEmpty()) body.append(" `").append(args.trim()).append("`");
            }
            String badges = badges(command, null);
            if (!badges.isEmpty()) body.append("\n").append(badges);

            if (!field(embed, "/" + command.getName(), body.toString())) {
                field(embed, "More", "This category does not fit on one page. Use the menu below.");
                break;
            }
        }

        int page = catalog.pageOf(category);
        Embeds.footer(embed, category + " • Page " + page + " of " + catalog.categories().size());
        return new Rendered(embed.build(), HelpControls.rows(catalog, viewer, categoryKey(category), category, null));
    }

    // ------------------------------------------------------------------
    // Detail card
    // ------------------------------------------------------------------

    private static Rendered detail(Bot bot, HelpCatalog catalog, Viewer viewer, Target target) {
        BunnyCommand command = target.command();
        BunnySubcommand sub = target.subcommand();
        String path = target.path();

        EmbedBuilder embed = base(bot).setTitle("📘 /" + path);
        String description = sub != null ? sub.getDescription() : command.getDescription();

        StringBuilder intro = new StringBuilder("*").append(describe(description)).append("*");
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
        usage.append("**Slash:** ").append(reference(bot, path));
        if (!args.isEmpty()) usage.append("\n`/").append(path).append(args).append("`");
        usage.append("\n**Mention:** ").append(mentionable
                ? "`@" + bot.name() + " " + path + args + "`"
                : "*not available, use the slash command*");

        List<String> aliases = sub != null ? sub.getAliases() : command.getAliases();
        if (mentionable && !aliases.isEmpty()) {
            String prefix = path.contains(" ") ? path.substring(0, path.lastIndexOf(' ') + 1) : "";
            List<String> shortcuts = new ArrayList<>();
            for (String alias : aliases) shortcuts.add("`@" + bot.name() + " " + prefix + alias + "`");
            usage.append("\n**Shortcuts:** ").append(String.join(" ", shortcuts));
        }
        if (sub == null && command.getUserContextName() != null)
            usage.append("\n**Right-click:** a user → **Apps → ").append(command.getUserContextName()).append("**");
        if (sub == null && command.defaultSubcommand() != null && mentionable)
            usage.append("\n`@").append(bot.name()).append(" ").append(command.getName()).append("` alone runs **")
                    .append(command.defaultSubcommand().getName()).append("**.");
        field(embed, "How to use", usage.toString());

        String declared = sub != null ? sub.getExample() : command.getExample();
        if ((declared != null && !declared.isBlank()) || !options.isEmpty())
            field(embed, "Example", example(declared, path, options, bot.name(), mentionable));

        if (options.isEmpty()) {
            field(embed, "Options", "None. Just run it.");
            return;
        }

        List<String> lines = new ArrayList<>();
        for (OptionData option : options) lines.add(optionLine(option));
        chunked(embed, "Options", lines);
    }

    /** A command with subcommands: one field per branch, each with its own clickable usage. */
    private static void branchFields(EmbedBuilder embed, Bot bot, BunnyCommand command) {
        List<Branch> branches = branches(command);
        int shown = 0;
        for (Branch branch : branches) {
            if (shown >= MAX_FIELDS - 1) break;
            StringBuilder body = new StringBuilder();
            body.append(describe(branch.subcommand().getDescription())).append("\n")
                    .append(reference(bot, branch.path()));
            String args = syntax(branch.subcommand().getOptions());
            if (!args.isEmpty()) body.append(" `").append(args.trim()).append("`");
            if (!field(embed, "/" + branch.path(), body.toString())) break;
            shown++;
        }
        int hidden = branches.size() - shown;
        if (hidden > 0) field(embed, "More", hidden + " more. Pick one from the menu below.");
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
    private static String example(String declared, String path, List<OptionData> options, String botName, boolean mentionable) {
        String slash;
        if (declared != null && !declared.isBlank()) {
            slash = declared.trim();
        } else {
            StringBuilder generated = new StringBuilder("/").append(path);
            for (OptionData option : options)
                if (option.isRequired()) generated.append(" ").append(option.getName()).append(":").append(sample(option));
            slash = generated.toString();
        }
        String result = "`" + slash + "`";
        if (mentionable)
            result += "\n`@" + botName + " " + (slash.startsWith("/") ? slash.substring(1) : slash) + "`";
        return result;
    }

    // ------------------------------------------------------------------
    // Text helpers
    // ------------------------------------------------------------------

    private static EmbedBuilder base(Bot bot) {
        EmbedBuilder embed = new EmbedBuilder().setColor(AppDesign.ColorCodes.DEFAULT).setTimestamp(Instant.now());
        if (bot.avatarUrl() != null) embed.setThumbnail(bot.avatarUrl());
        return embed;
    }

    /**
     * A clickable {@code </path:id>} when the command is deployed, plain code otherwise.
     * Discord only makes runnable paths clickable, so a branched root is never passed here.
     */
    static String reference(Bot bot, String path) {
        String root = path.contains(" ") ? path.substring(0, path.indexOf(' ')) : path;
        String id = bot.commandIds().get(root);
        return id == null ? "`/" + path + "`" : "</" + path + ":" + id + ">";
    }

    /** Overview entry: clickable when runnable, bold code for a command that needs a subcommand. */
    private static String rootReference(Bot bot, BunnyCommand command) {
        String marker = command.isMentionEnabled() ? " 💬" : "";
        if (command.hasBranches()) return "**`/" + command.getName() + "`**" + marker;
        return reference(bot, command.getName()) + marker;
    }

    private static String badges(BunnyCommand command, BunnySubcommand sub) {
        List<String> badges = new ArrayList<>();
        if (!command.getUserPermissions().isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Permission permission : command.getUserPermissions()) names.add(permission.getName());
            badges.add("🔒 Needs " + String.join(", ", names));
        }
        if (command.isDeveloperOnly() || (sub != null && sub.isDeveloperOnly())) badges.add("🛠️ Bot owner only");
        if (!command.isDmEnabled()) badges.add("🏠 Servers only");
        if (command.isNsfw() || (sub != null && sub.isNsfw())) badges.add("🔞 NSFW channels");
        int cooldown = sub != null && sub.getCooldown() > 0 ? sub.getCooldown() : command.getCooldown();
        if (cooldown > 0) badges.add("⏱️ " + cooldown + "s cooldown");
        if (command.isMentionEnabled() && (sub == null || sub.isMentionEnabled())) badges.add("💬 Mention works");
        return String.join(" · ", badges);
    }
}
