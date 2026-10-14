package org.bunnys.commands.moderation;

import net.dv8tion.jda.api.Permission;
import org.bunnys.bunnynexus.moderation.Moderation;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;

/** Registration only; see {@link Moderation}. Hidden in Discord from members without the permission. */
@SuppressWarnings("unused") // Discovered reflectively by CommandLoader.
public final class Ban extends BunnyCommand {
    public Ban(BunnyHub client) {
        super(client);
        setName("ban");
        setCategory("Moderation");
        setDescription("Ban someone from the server. Works even if they already left.");
        setExample("/ban user:@spammer delete_messages:Last 24 hours reason:Spam");
        setDmEnabled(false);
        setMentionEnabled(false);
        addUserPermissions(Permission.BAN_MEMBERS);
        setCooldown(2);
        addOption(Moderation.userOption("Who to ban"));
        addOption(Moderation.reasonOption());
        addOption(Moderation.deleteMessagesOption());
    }

    @Override public void execute(BunnyHub client, CommandContext ctx) { Moderation.run(Moderation.Action.BAN, ctx); }
}
