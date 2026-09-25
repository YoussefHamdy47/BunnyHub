package org.bunnys.handler.commands;

import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.SystemEmbeds;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One branch of a {@link BunnyCommand}, reachable as {@code /parent name} or
 * {@code @BotName parent name}.
 */
public abstract class BunnySubcommand {

    private String name;
    private String description;
    private String example;

    /** Extra names accepted on the mention path. See {@link BunnyCommand#addAliases}. */
    private final List<String> aliases = new ArrayList<>();

    // Local overrides; where unset, the parent command's value applies.
    private int cooldown = 0;
    private boolean developerOnly = false;
    private boolean adminOnly = false;
    private boolean nsfw = false;
    private boolean mentionEnabled = true;

    private final List<OptionData> options = new ArrayList<>();

    public BunnySubcommand setName(String name) {
        this.name = name;
        return this;
    }

    public BunnySubcommand setDescription(String description) {
        this.description = description;
        return this;
    }

    /** See {@link BunnyCommand#addAliases}. Call after {@link #setName}. */
    public BunnySubcommand addAliases(String... values) {
        Aliases.addTo(aliases, name, values);
        return this;
    }

    /** True when this subcommand answers to {@code candidate}, by name or alias. */
    public boolean matches(String candidate) {
        return Aliases.matches(name, aliases, candidate);
    }

    /** Extra names accepted on the mention path. Read-only. */
    public List<String> getAliases() {
        return Collections.unmodifiableList(aliases);
    }

    /** See {@link BunnyCommand#setExample}. Optional; the help menu generates a fallback. */
    public BunnySubcommand setExample(String example) {
        this.example = example;
        return this;
    }

    public BunnySubcommand setCooldown(int seconds) {
        if (seconds < 0 || seconds > 7200) throw new IllegalArgumentException("Cooldown must be between 0 and 7200 seconds.");
        this.cooldown = seconds;
        return this;
    }

    public BunnySubcommand setDeveloperOnly(boolean developerOnly) {
        this.developerOnly = developerOnly;
        return this;
    }

    public BunnySubcommand setAdminOnly(boolean adminOnly) {
        this.adminOnly = adminOnly;
        return this;
    }

    public BunnySubcommand setNsfw(boolean nsfw) {
        this.nsfw = nsfw;
        return this;
    }

    /** False for subcommands that must open a modal; see {@link BunnyCommand#setMentionEnabled}. */
    public BunnySubcommand setMentionEnabled(boolean mentionEnabled) {
        this.mentionEnabled = mentionEnabled;
        return this;
    }

    public BunnySubcommand addOption(OptionData option) {
        this.options.add(option);
        return this;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getExample() { return example; }
    public int getCooldown() { return cooldown; }
    public boolean isDeveloperOnly() { return developerOnly; }
    public boolean isAdminOnly() { return adminOnly; }
    public boolean isNsfw() { return nsfw; }
    public boolean isMentionEnabled() { return mentionEnabled; }

    /** Read-only; the mention parser and the help menu both walk this. */
    public List<OptionData> getOptions() {
        return Collections.unmodifiableList(options);
    }

    public SubcommandData buildData() {
        SubcommandData data = new SubcommandData(name, description);
        data.addOptions(options);
        return data;
    }

    public List<String> autocomplete(BunnyHub client, CommandAutoCompleteInteractionEvent event) {
        return List.of();
    }

    public void execute(BunnyHub client, CommandContext ctx) {
        BunnyLog.error("CRITICAL: Subcommand '" + this.name + "' was triggered but has no execute() implementation");
        ctx.reply(SystemEmbeds.missingImplementation(
                this.name, client.getCommandRegistry().getDeveloperIds()), true);
    }
}
