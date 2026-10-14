package org.bunnys.commands.freebies;

import net.dv8tion.jda.api.Permission;
import org.bunnys.bunnynexus.commands.freebies.FreebieSettingsCommands;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;

/** Registration only; opens the dashboard from {@link FreebieSettingsCommands}. */
@SuppressWarnings("unused") // Discovered reflectively by CommandLoader.
public final class Freebie extends BunnyCommand {
    public Freebie(BunnyHub client) {
        super(client);
        setName("freebie");
        setCategory("Server");
        setDescription("Post free games from Epic, Steam, GOG and more in this server.");
        setDmEnabled(false);
        setMentionEnabled(false);
        setDeferBeforeDispatch(true);
        addUserPermissions(Permission.MANAGE_SERVER);
        setCooldown(3);
    }

    @Override public boolean defaultEphemeral(CommandContext context) { return true; }

    @Override public void execute(BunnyHub client, CommandContext ctx) { FreebieSettingsCommands.openDashboard(ctx); }
}
