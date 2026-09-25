package org.bunnys.handler.commands;

import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A named bundle of subcommands under one parent, giving {@code /parent group action}.
 *
 * <p>Discord allows exactly one level of grouping, and this mirrors that: a group holds
 * {@link BunnySubcommand}s and never another group. It carries no {@code execute} of its
 * own - a group is a namespace, not a destination, and invoking one without a
 * subcommand is impossible through Discord's UI.
 *
 * <p>Both routers resolve groups before plain subcommands, so {@code /admin logging set}
 * and {@code @BotName admin logging set} reach the same handler.
 */
public final class BunnySubcommandGroup {

    private final String name;
    private final String description;

    /** Insertion-ordered so the help menu lists actions the way they were written. */
    private final Map<String, BunnySubcommand> subcommands = new LinkedHashMap<>();

    public BunnySubcommandGroup(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public BunnySubcommandGroup addSubcommand(BunnySubcommand subcommand) {
        subcommands.put(subcommand.getName(), subcommand);
        return this;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }

    public Map<String, BunnySubcommand> getSubcommands() {
        return Collections.unmodifiableMap(subcommands);
    }

    /**
     * One of this group's subcommands, by name or alias.
     *
     * <p>Exact name first, so the slash path never pays for the alias scan.
     */
    public BunnySubcommand resolve(String action) {
        BunnySubcommand direct = subcommands.get(action);
        if (direct != null)
            return direct;

        for (BunnySubcommand candidate : subcommands.values())
            if (candidate.matches(action))
                return candidate;

        return null;
    }

    public SubcommandGroupData buildData() {
        SubcommandGroupData data = new SubcommandGroupData(name, description);
        for (BunnySubcommand subcommand : subcommands.values())
            data.addSubcommands(subcommand.buildData());
        return data;
    }
}
