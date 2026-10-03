package org.bunnys.commands.freebies;

import net.dv8tion.jda.api.Permission;
import org.bunnys.bunnynexus.commands.freebies.FreebieAdminCommands;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;

/** Registration only. Developer-only, hidden from non-admins, and re-checked against FREEBIE_OWNER_IDS. */
@SuppressWarnings("unused") // Discovered reflectively by CommandLoader.
public final class FreebieAdmin extends BunnyCommand {
    public FreebieAdmin(BunnyHub client) {
        super(client);
        setName("freebie-admin");
        setCategory("Owner");
        setDescription("Bot owner controls for free-game alerts.");
        setDeveloperOnly(true);
        setDmEnabled(false);
        setMentionEnabled(false);
        setDeferBeforeDispatch(true);
        addUserPermissions(Permission.ADMINISTRATOR);
        setCooldown(5);
        addSubcommand(FreebieAdminCommands.status());
        addSubcommand(FreebieAdminCommands.pause());
        addSubcommand(FreebieAdminCommands.resume());
        addSubcommand(FreebieAdminCommands.check());
        addSubcommand(new org.bunnys.bunnynexus.commands.freebies.FreebieHistoryCommands(false));
        addSubcommand(new org.bunnys.bunnynexus.commands.freebies.FreebieHistoryCommands(true));
    }

    @Override public boolean defaultEphemeral(CommandContext context) { return true; }
}
