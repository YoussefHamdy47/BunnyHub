package org.bunnys.handler.commands;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.SystemEmbeds;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A command, invokable as either {@code /name} or {@code @BotName name}.
 *
 * <p>{@link #execute} takes a {@link CommandContext} rather than a slash event, so one
 * body serves both entry points. The {@link OptionData} declared here drives three
 * things at once: the slash command Discord registers, the argument grammar the mention
 * router parses, and the usage lines the help menu prints.
 */
public abstract class BunnyCommand {
    private boolean deferBeforeDispatch;
    private String userContextName;
    /** Optional user-menu entry which delegates to this command's implementation. */
    public void setUserContextName(String name) { userContextName = name; }
    public String getUserContextName() { return userContextName; }
    public net.dv8tion.jda.api.interactions.commands.build.CommandData buildUserContextCommandData() {
        if (userContextName == null) throw new IllegalStateException("No user context menu configured.");
        var data = Commands.user(userContextName)
                .setContexts(dmEnabled ? InteractionContextType.ALL : java.util.Set.of(InteractionContextType.GUILD))
                .setNSFW(nsfw);
        if (!userPermissions.isEmpty()) data.setDefaultPermissions(DefaultMemberPermissions.enabledFor(userPermissions));
        return data;
    }

    public List<net.dv8tion.jda.api.interactions.commands.build.CommandData> buildCommandDefinitions() {
        return userContextName == null ? List.of(buildCommandData())
                : List.of(buildCommandData(), buildUserContextCommandData());
    }
    /** Enable for commands that always answer with a message rather than opening a modal. */
    public void setDeferBeforeDispatch(boolean value) { deferBeforeDispatch = value; }
    public boolean isDeferBeforeDispatch() { return deferBeforeDispatch; }
    public boolean defaultEphemeral(CommandContext context) { return false; }

    public String pathOf(BunnySubcommand subcommand) {
        if (subcommand == null) return getName();
        for (var group : getSubcommandGroups().values())
            if (group.getSubcommands().containsValue(subcommand))
                return getName() + "." + group.getName() + "." + subcommand.getName();
        return getName() + "." + subcommand.getName();
    }

    protected final BunnyHub client;

    private String name;
    private String description;
    private String category = "Uncategorized";

    /**
     * Extra names this command answers to.
     *
     * <p>Mention invocations only. Discord has no concept of an alias for a slash
     * command, and registering each one as its own command would double the list every
     * user sees to save them four keystrokes. So {@code @BotName lb} works and
     * {@code /lb} does not, which is why the help menu prints the aliases next to the
     * real name rather than implying they are interchangeable everywhere.
     */
    private final List<String> aliases = new ArrayList<>();

    /**
     * The subcommand a bare mention falls through to, or null.
     *
     * <p>Mention invocations only, and necessarily so: Discord will not let a slash
     * command with subcommands be invoked without one, so {@code /ooc} cannot exist while
     * {@code /ooc get} does. {@code @BotName ooc} has no such restriction, and for a
     * command whose obvious action is one of several, making people type that action
     * every time is friction the mention path does not need.
     *
     * <p>The token is <em>not</em> consumed when this fires, so any arguments still reach
     * the default subcommand as its own.
     */
    private String defaultSubcommand;

    /** Null unless the command supplies one; the help menu generates a fallback. */
    private String example;

    // Feature Flags
    private boolean developerOnly = false;
    private boolean adminOnly = false;
    private boolean testOnly = false;
    private boolean nsfw = false;
    private boolean dmEnabled = true;
    private boolean developerBypass = false;
    private boolean mentionEnabled = true;
    private int cooldown = 0;

    private final List<OptionData> options = new ArrayList<>();
    private final List<Permission> userPermissions = new ArrayList<>();
    private final List<Permission> appPermissions = new ArrayList<>();

    /** Insertion-ordered so the help menu lists subcommands the way they were written. */
    private final Map<String, BunnySubcommand> subcommands = new LinkedHashMap<>();
    private final Map<String, BunnySubcommandGroup> subcommandGroups = new LinkedHashMap<>();

    public BunnyCommand(BunnyHub client) {
        this.client = client;
    }

    // ------------------------------------------------------------------
    // Setters
    // ------------------------------------------------------------------

    public BunnyCommand setName(String name) {
        this.name = name;
        return this;
    }

    public BunnyCommand setDescription(String description) {
        this.description = description;
        return this;
    }

    public BunnyCommand setCategory(String category) {
        this.category = category;
        return this;
    }

    /**
     * Adds alternative names for the mention path.
     *
     * <p>Folded to lower case and de-duplicated, and an alias equal to the command's own
     * name is dropped rather than stored twice. Call it after {@link #setName} so that
     * last check has a name to compare against.
     */
    public BunnyCommand addAliases(String... values) {
        Aliases.addTo(aliases, name, values);
        return this;
    }

    /**
     * Names the subcommand a bare {@code @BotName command} runs.
     *
     * <p>See {@link #defaultSubcommand}. Has no effect on the slash path, which Discord
     * requires to name a subcommand explicitly.
     */
    public BunnyCommand setDefaultSubcommand(String name) {
        this.defaultSubcommand = name;
        return this;
    }

    /**
     * The subcommand a bare mention should run, or null when there is none.
     *
     * <p>Resolved rather than stored so a name that no longer exists reads as "no
     * default" instead of routing nowhere.
     */
    public BunnySubcommand defaultSubcommand() {
        return defaultSubcommand == null ? null : resolveSubcommand(defaultSubcommand);
    }

    public BunnyCommand setDeveloperOnly(boolean developerOnly) {
        this.developerOnly = developerOnly;
        return this;
    }

    public BunnyCommand setAdminOnly(boolean adminOnly) {
        this.adminOnly = adminOnly;
        return this;
    }

    public BunnyCommand setTestOnly(boolean testOnly) {
        this.testOnly = testOnly;
        return this;
    }

    public BunnyCommand setNsfw(boolean nsfw) {
        this.nsfw = nsfw;
        return this;
    }

    public BunnyCommand setDmEnabled(boolean dmEnabled) {
        this.dmEnabled = dmEnabled;
        return this;
    }

    public BunnyCommand setDeveloperBypass(boolean developerBypass) {
        this.developerBypass = developerBypass;
        return this;
    }

    /**
     * Whether {@code @BotName name} may invoke this.
     *
     * <p>Set false only when the command genuinely cannot work from a message - in
     * practice, when it must open a modal, which Discord permits solely in response to
     * an interaction. The help menu reads this and prints the slash form alone.
     */
    public BunnyCommand setMentionEnabled(boolean mentionEnabled) {
        this.mentionEnabled = mentionEnabled;
        return this;
    }

    /**
     * A concrete, copy-pasteable invocation for the help detail card.
     *
     * <p>Optional. Where it is unset the help menu synthesises one from the declared
     * options, which is always correct but rarely inspiring - {@code query:text} rather
     * than {@code query:Legoshi}. Set it wherever a real value teaches more than a
     * placeholder does.
     *
     * <p>Write it in slash form; the help menu rewrites it into the mention form.
     */
    public BunnyCommand setExample(String example) {
        this.example = example;
        return this;
    }

    public BunnyCommand setCooldown(int seconds) {
        if (seconds < 0 || seconds > 7200) throw new IllegalArgumentException("Cooldown must be between 0 and 7200 seconds.");
        this.cooldown = seconds;
        return this;
    }

    public BunnyCommand addOption(OptionData option) {
        this.options.add(option);
        return this;
    }

    public BunnyCommand addUserPermissions(Permission... permissions) {
        this.userPermissions.addAll(Arrays.asList(permissions));
        return this;
    }

    public BunnyCommand addAppPermissions(Permission... permissions) {
        this.appPermissions.addAll(Arrays.asList(permissions));
        return this;
    }

    public BunnyCommand addSubcommand(BunnySubcommand subcommand) {
        this.subcommands.put(subcommand.getName(), subcommand);
        return this;
    }

    /** Adds a {@code /parent group action} namespace. Discord permits one level only. */
    public BunnyCommand addSubcommandGroup(BunnySubcommandGroup group) {
        this.subcommandGroups.put(group.getName(), group);
        return this;
    }

    // ------------------------------------------------------------------
    // Getters
    // ------------------------------------------------------------------

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    /** Extra names accepted on the mention path. Read-only. */
    public List<String> getAliases() { return Collections.unmodifiableList(aliases); }
    public String getExample() { return example; }
    public boolean isDeveloperOnly() { return developerOnly; }
    public boolean isAdminOnly() { return adminOnly; }
    public boolean isTestOnly() { return testOnly; }
    public boolean isNsfw() { return nsfw; }
    public boolean isDmEnabled() { return dmEnabled; }
    public boolean isDeveloperBypass() { return developerBypass; }
    public boolean isMentionEnabled() { return mentionEnabled; }
    public int getCooldown() { return cooldown; }
    public List<Permission> getUserPermissions() { return userPermissions; }
    public List<Permission> getAppPermissions() { return appPermissions; }
    public Map<String, BunnySubcommand> getSubcommands() { return subcommands; }
    public Map<String, BunnySubcommandGroup> getSubcommandGroups() { return subcommandGroups; }

    /** True when this command cannot be invoked without naming a group or subcommand. */
    public boolean hasBranches() {
        return !subcommands.isEmpty() || !subcommandGroups.isEmpty();
    }

    /**
     * Resolves {@code group} then {@code action} to a concrete subcommand.
     *
     * @param group  the group name, or null for a top-level subcommand
     * @param action the subcommand name
     * @return the subcommand, or null if either name is unknown
     */
    public BunnySubcommand resolve(String group, String action) {
        if (action == null)
            return null;

        if (group == null)
            return resolveSubcommand(action);

        BunnySubcommandGroup target = resolveGroup(group);
        return target == null ? null : target.resolve(action);
    }

    /**
     * A direct subcommand by name or alias.
     *
     * <p>The exact-name lookup runs first, so the slash path, which always sends the
     * canonical name, never pays for the alias scan.
     */
    public BunnySubcommand resolveSubcommand(String action) {
        BunnySubcommand direct = subcommands.get(action);
        if (direct != null)
            return direct;

        for (BunnySubcommand candidate : subcommands.values())
            if (candidate.matches(action))
                return candidate;

        return null;
    }

    /** A subcommand group by name. Groups are a namespace, not a command, and take no aliases. */
    public BunnySubcommandGroup resolveGroup(String group) {
        return group == null ? null : subcommandGroups.get(group);
    }

    /** True when this command answers to {@code candidate}, by name or alias. */
    public boolean matches(String candidate) {
        return Aliases.matches(name, aliases, candidate);
    }

    /** Read-only; the mention parser and the help menu both walk this. */
    public List<OptionData> getOptions() {
        return Collections.unmodifiableList(options);
    }

    // ------------------------------------------------------------------
    // Behaviour
    // ------------------------------------------------------------------

    public SlashCommandData buildCommandData() {
        SlashCommandData data = Commands.slash(name, description);

        if (dmEnabled)
            data.setContexts(InteractionContextType.ALL);
        else
            data.setContexts(InteractionContextType.GUILD);

        data.setNSFW(nsfw);
        data.addOptions(options);

        for (BunnySubcommand sub : subcommands.values())
            data.addSubcommands(sub.buildData());

        for (BunnySubcommandGroup group : subcommandGroups.values())
            data.addSubcommandGroups(group.buildData());

        if (!userPermissions.isEmpty())
            data.setDefaultPermissions(DefaultMemberPermissions.enabledFor(userPermissions));

        return data;
    }

    /** Slash-only; Discord has no autocomplete for message content. */
    public List<String> autocomplete(BunnyHub client, CommandAutoCompleteInteractionEvent event) {
        return List.of();
    }

    public void execute(BunnyHub client, CommandContext ctx) {
        BunnyLog.error("CRITICAL: Command '" + this.name + "' was triggered but has no execute() implementation");
        ctx.reply(SystemEmbeds.missingImplementation(
                this.name, client.getCommandRegistry().getDeveloperIds()), true);
    }
}
