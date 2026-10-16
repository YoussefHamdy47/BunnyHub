package org.bunnys.commands.freebies;

import net.dv8tion.jda.api.interactions.IntegrationType;
import org.bunnys.bunnynexus.commands.freebies.FreebieAdminCommands;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;

/**
 * Registration only. Installed to the developers' own accounts rather than to servers, so it shows up in their
 * slash menu everywhere and in nobody else's. Developer-only, and re-checked against FREEBIE_OWNER_IDS.
 */
@SuppressWarnings("unused") // Discovered reflectively by CommandLoader.
public final class FreebieAdmin extends BunnyCommand {
    public FreebieAdmin(BunnyHub client) {
        super(client);
        setName("freebie-admin");
        setCategory("Owner");
        setDescription("Bot owner controls for free-game alerts.");
        setDeveloperOnly(true);
        setInstallTypes(IntegrationType.USER_INSTALL);
        setMentionEnabled(false);
        setDeferBeforeDispatch(true);
        setCooldown(5);
        addSubcommand(FreebieAdminCommands.status());
        addSubcommand(FreebieAdminCommands.pause());
        addSubcommand(FreebieAdminCommands.resume());
        addSubcommand(FreebieAdminCommands.check());
        addSubcommand(new org.bunnys.bunnynexus.commands.freebies.FreebieConnectCommand());
        addSubcommand(new org.bunnys.bunnynexus.commands.freebies.FreebieSourceCommand());
        addSubcommand(new org.bunnys.bunnynexus.commands.freebies.FreebieHistoryCommands(false));
        addSubcommand(new org.bunnys.bunnynexus.commands.freebies.FreebieHistoryCommands(true));
    }

    @Override public boolean defaultEphemeral(CommandContext context) { return true; }
}

