package org.bunnys.commands;

import net.dv8tion.jda.api.interactions.IntegrationType;
import org.bunnys.bunnynexus.commands.mailbridge.MailBridgeCommands;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;

/** Registration only; see {@link MailBridgeCommands}. On the developers' own accounts, like /freebie-admin. */
@SuppressWarnings("unused") // Discovered reflectively by CommandLoader.
public final class MailBridgeCommand extends BunnyCommand {
    public MailBridgeCommand(BunnyHub client) {
        super(client);
        setName("mail-bridge");
        setCategory("Owner");
        setDescription("Check and test the Gmail task notifications.");
        setDeveloperOnly(true);
        setInstallTypes(IntegrationType.USER_INSTALL);
        setMentionEnabled(false);
        setDeferBeforeDispatch(true);
        setCooldown(5);
        addSubcommand(new MailBridgeCommands.Status());
        addSubcommand(new MailBridgeCommands.Check());
        addSubcommand(new MailBridgeCommands.Diagnose());
        addSubcommand(new MailBridgeCommands.DryRun());
        addSubcommand(new MailBridgeCommands.Test());
    }

    @Override public boolean defaultEphemeral(CommandContext context) { return true; }
}
