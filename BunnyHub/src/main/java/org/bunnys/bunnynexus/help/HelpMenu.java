package org.bunnys.bunnynexus.help;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.MessageEmbed;
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

    private static final String TAGLINE = "Study timers, free-game alerts and moderation tools.";

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
            if (target != null) return HelpDetail.render(bot, catalog, viewer, target);
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
            if (target != null) return HelpDetail.render(bot, catalog, viewer, target);
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
        EmbedBuilder embed = base(bot).setTitle(bot.name() + " Help");

        StringBuilder intro = new StringBuilder();
        if (notice != null) intro.append(notice).append("\n\n");
        intro.append(TAGLINE).append("\n")
                .append("Type `/` to run a command, or pick one from the menu below to see how it works.");
        embed.setDescription(intro.toString());

        if (catalog.isEmpty()) {
            field(embed, "Nothing here yet", "No commands are available to you right now.");
        } else {
            for (Map.Entry<String, List<BunnyCommand>> entry : catalog.categories().entrySet()) {
                StringBuilder lines = new StringBuilder();
                for (BunnyCommand command : entry.getValue()) {
                    lines.append(rootReference(bot, command)).append(" ")
                            .append(shorten(command.getDescription(), 60)).append("\n");
                    if (command.hasBranches()) lines.append(branchList(bot, command)).append("\n");
                }
                field(embed, entry.getKey(), lines.toString());
            }
        }

        int count = catalog.all().size();
        Embeds.footer(embed, count + (count == 1 ? " command" : " commands"));
        return new Rendered(embed.build(), HelpControls.rows(catalog, viewer, OVERVIEW, null, null));
    }

    // ------------------------------------------------------------------
    // Category page
    // ------------------------------------------------------------------

    private static Rendered categoryPage(Bot bot, HelpCatalog catalog, Viewer viewer, String category) {
        List<BunnyCommand> inCategory = catalog.categories().get(category);
        EmbedBuilder embed = base(bot).setTitle(category);
        embed.setDescription("Pick a command from the menu below for its options and an example.");

        for (BunnyCommand command : inCategory) {
            StringBuilder body = new StringBuilder();
            body.append(describe(command.getDescription())).append("\n");
            if (command.hasBranches()) {
                for (Branch branch : branches(command))
                    body.append(reference(bot, branch.path())).append(" ")
                            .append(shorten(branch.subcommand().getDescription(), 70)).append("\n");
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
    // Text helpers
    // ------------------------------------------------------------------

    static EmbedBuilder base(Bot bot) {
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

    /** A branched command's subcommands on one indented line, each clickable, for the overview. */
    private static String branchList(Bot bot, BunnyCommand command) {
        List<String> refs = new ArrayList<>();
        for (Branch branch : branches(command)) refs.add(reference(bot, branch.path()));
        return "\u2003" + String.join("  ", refs);
    }

    /** Overview entry: clickable when runnable, bold code for a command that needs a subcommand. */
    private static String rootReference(Bot bot, BunnyCommand command) {
        if (command.hasBranches()) return "**`/" + command.getName() + "`**";
        return reference(bot, command.getName());
    }

    static String badges(BunnyCommand command, BunnySubcommand sub) {
        List<String> badges = new ArrayList<>();
        if (!command.getUserPermissions().isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Permission permission : command.getUserPermissions()) names.add(permission.getName());
            badges.add("Needs " + String.join(", ", names));
        }
        if (command.isDeveloperOnly() || (sub != null && sub.isDeveloperOnly())) badges.add("Bot owner only");
        if (!command.isDmEnabled()) badges.add("Servers only");
        if (command.isNsfw() || (sub != null && sub.isNsfw())) badges.add("NSFW channels only");
        return badges.isEmpty() ? "" : "-# " + String.join(" · ", badges);
    }
}
