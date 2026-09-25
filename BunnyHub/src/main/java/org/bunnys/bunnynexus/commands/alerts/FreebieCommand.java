package org.bunnys.bunnynexus.commands.alerts;

import net.dv8tion.jda.api.Permission;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.bunnynexus.alerts.application.ConfigurationService;

/** Configured before explicit registration by the composition root. */
public final class FreebieCommand extends BunnyCommand {
    public FreebieCommand(BunnyHub client, ConfigurationService service) {
        super(client);
        var actions = new FreebieActions(service);
        setName("freebie");
        setDescription("Manage this server's free-game alert settings.");
        setDmEnabled(false);
        setMentionEnabled(false);
        setDeferBeforeDispatch(true);
        addUserPermissions(Permission.MANAGE_SERVER);
        setCooldown(3);
        addSubcommand(new FreebieStatus(actions));
        addSubcommand(new FreebieSetup(actions));
        addSubcommand(new FreebieToggle(actions));
        addSubcommand(new FreebieRemove(actions));
    }
    @Override public boolean defaultEphemeral(CommandContext context) { return true; }
}
