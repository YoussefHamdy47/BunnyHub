package org.bunnys.handler.commands;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import org.bunnys.handler.BunnyHub;
import org.bunnys.utils.BunnyLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The live command table.
 *
 * <p>The parallel {@code BunnyMessageCommand} registry - a name map, an alias map, a
 * loader pass and a getter - has been removed. It had <b>zero</b> implementations in
 * this project, and with the Message Content intent gone it could never gain one:
 * prefix commands cannot be read at all. Mention invocations resolve against the same
 * {@link BunnyCommand} table slash commands use, so there is one source of truth for
 * what a command is.
 */
public class CommandRegistry {

    private final BunnyHub client;
    private record Routes(Map<String, BunnyCommand> commands, Map<String, BunnyCommand> aliases,
                          Map<String, BunnyCommand> userContexts) {
        static Routes empty() { return new Routes(Map.of(), Map.of(), Map.of()); }
    }
    private volatile Routes routes = Routes.empty();

    public BunnyCommand resolveUserContext(String name) { return routes.userContexts().get(name); }
    private final List<String> developerIds;
    private final List<String> developerIdsView;
    private final List<String> testServerIds;

    public CommandRegistry(BunnyHub client, List<String> developerIds, List<String> testServerIds) {
        this.client = client;
        this.developerIds = List.copyOf(developerIds);
        this.developerIdsView = Collections.unmodifiableList(this.developerIds);
        this.testServerIds = List.copyOf(testServerIds);
    }

    public List<String> getTestServerIds() { return testServerIds; }

    public synchronized void registerCommand(BunnyCommand command) {
        java.util.Objects.requireNonNull(command, "Command");
        if (command.getName() == null || command.getName().isBlank())
            throw new IllegalArgumentException("Command name is required.");
        String name = command.getName().toLowerCase(java.util.Locale.ROOT);
        Routes before = routes;
        Map<String, BunnyCommand> commands = new HashMap<>(before.commands());
        Map<String, BunnyCommand> aliases = new HashMap<>(before.aliases());
        Map<String, BunnyCommand> userContexts = new HashMap<>(before.userContexts());
        BunnyCommand existing = commands.get(name);
        if (existing == command) return;
        if (existing != null) throw new IllegalArgumentException("Duplicate command: " + name);
        String context = command.getUserContextName();
        if (context != null && userContexts.containsKey(context))
            throw new IllegalArgumentException("Duplicate user context command: " + context);
        // Validate Discord definitions before publishing any route.
        command.buildCommandDefinitions().forEach(data -> data.toData());
        registerAliases(command, name, commands, aliases);
        commands.put(name, command);
        if (context != null) userContexts.put(context, command);
        routes = new Routes(Map.copyOf(commands), Map.copyOf(aliases), Map.copyOf(userContexts));
    }
    /**
     * Records a command's aliases, refusing any that would be ambiguous.
     *
     * <p>An alias that collides with a real command name, or with an alias already
     * claimed by a different command, is dropped and logged rather than silently
     * shadowing whichever registered last. Reflective loading gives no ordering
     * guarantee, so "last one wins" would mean a convenience name that works or does not
     * depending on classpath order.
     */
    private void registerAliases(BunnyCommand command, String canonical, Map<String, BunnyCommand> commands, Map<String, BunnyCommand> aliases) {
        for (String alias : command.getAliases()) {
            if (commands.containsKey(alias)) {
                BunnyLog.warning("[CommandRegistry] Alias '" + alias + "' on '" + canonical
                        + "' is already a command name. Ignored.");
                continue;
            }

            BunnyCommand claimed = aliases.putIfAbsent(alias, command);
            if (claimed != null && claimed != command)
                BunnyLog.warning("[CommandRegistry] Alias '" + alias + "' is claimed by '"
                        + claimed.getName() + "'; '" + canonical + "' cannot also use it.");
        }
    }

    /**
     * A command by its name or one of its aliases, or null.
     *
     * <p>The one lookup both listeners use. Slash interactions always arrive under the
     * canonical name and hit the first map; only the mention path reaches the second.
     */
    public BunnyCommand resolveCommand(String name) {
        if (name == null || name.isEmpty())
            return null;

        String key = name.toLowerCase(java.util.Locale.ROOT);
        Routes current = routes;
        BunnyCommand direct = current.commands().get(key);

        return direct != null ? direct : current.aliases().get(key);
    }

    public void deployCommands() { deployCommands(client.getJDA()); }

    public void deployCommands(net.dv8tion.jda.api.JDA jda) {
        List<CommandData> global = new ArrayList<>();
        Map<String, List<CommandData>> perGuild = new HashMap<>();

        for (String guildId : testServerIds) perGuild.put(guildId, new ArrayList<>());
        for (BunnyCommand command : routes.commands().values()) {
            if (command.isTestOnly())
                for (String guildId : testServerIds)
                    perGuild.computeIfAbsent(guildId, k -> new ArrayList<>()).addAll(command.buildCommandDefinitions());
            else
                global.addAll(command.buildCommandDefinitions());
        }

        jda.updateCommands().addCommands(global).queue(
                ok -> BunnyLog.success("[CommandRegistry] Registered " + global.size() + " global commands."),
                err -> BunnyLog.error("[CommandRegistry] Global deploy failed", err));

        perGuild.forEach((guildId, list) -> {
            Guild guild = jda.getGuildById(guildId);
            if (guild == null) {
                BunnyLog.warning("[CommandRegistry] Test guild " + guildId + " is not reachable; skipped.");
                return;
            }
            guild.updateCommands().addCommands(list).queue(
                    ok -> BunnyLog.success("[CommandRegistry] Registered " + list.size()
                            + " test commands to guild " + guildId + "."),
                    err -> BunnyLog.error("[CommandRegistry] Guild deploy failed for "
                            + guildId, err));
        });
    }

    public synchronized int clearCommands() {
        int count = getCommandCount();
        routes = Routes.empty();
        return count;
    }

    public int getCommandCount() {
        Routes current = routes; return current.commands().size() + current.userContexts().size();
    }

    /**
     * The command table, read-only.
     *
     * <p>Was the live map. Every caller only reads it - the help menu, both listeners,
     * the autocomplete router - but handing out a mutable handle to the thing that
     * defines what the bot can do invites exactly one bug, and it would be a strange one
     * to track down.
     */
    public Map<String, BunnyCommand> getCommands() {
        return routes.commands();
    }

    /** Read-only, for the same reason. */
    public List<String> getDeveloperIds() {
        return developerIdsView;
    }
}
