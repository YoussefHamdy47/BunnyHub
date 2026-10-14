package org.bunnys.commands.moderation;

import net.dv8tion.jda.api.Permission;
import org.bunnys.bunnynexus.moderation.Moderation;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;

/** Registration only; see {@link Moderation}. Hidden in Discord from members without the permission. */
@SuppressWarnings("unused") // Discovered reflectively by CommandLoader.
public final class Unban extends BunnyCommand {
    public Unban(BunnyHub client) {
        super(client);
        setName("unban");
        setCategory("Moderation");
        setDescription("Let a banned user join the server again.");
        setExample("/unban user:123456789012345678");
        setDmEnabled(false);
        setMentionEnabled(false);
        addUserPermissions(Permission.BAN_MEMBERS);
        setCooldown(2);
        addOption(Moderation.userOption("Who to unban (paste their user ID if they are not listed)"));
        addOption(Moderation.reasonOption());
    }

    @Override public void execute(BunnyHub client, CommandContext ctx) { Moderation.run(Moderation.Action.UNBAN, ctx); }
}
