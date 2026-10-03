package org.bunnys.commands.freebies;

import net.dv8tion.jda.api.Permission;
import org.bunnys.bunnynexus.commands.freebies.FreebieSettingsCommands;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;

/** Registration only; implementations live in {@link FreebieSettingsCommands}. */
@SuppressWarnings("unused") // Discovered reflectively by CommandLoader.
public final class Freebie extends BunnyCommand {
    public Freebie(BunnyHub client) {
        super(client);
        setName("freebie");
        setCategory("Free Games");
        setDescription("Get free-game alerts from Epic, Steam, GOG and more in this server.");
        setDmEnabled(false);
        setMentionEnabled(false);
        setDeferBeforeDispatch(true);
        addUserPermissions(Permission.MANAGE_SERVER);
        setCooldown(3);
        addSubcommand(new FreebieSettingsCommands.Dashboard());
        addSubcommand(new FreebieSettingsCommands.Setup());
        addSubcommand(new FreebieSettingsCommands.Remove());
        addSubcommand(new FreebieSettingsCommands.Status());
        addSubcommand(new FreebieSettingsCommands.Test());
    }

    @Override public boolean defaultEphemeral(CommandContext context) { return true; }
}
