package org.bunnys.commands.moderation;

import net.dv8tion.jda.api.Permission;
import org.bunnys.bunnynexus.moderation.Moderation;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;

/** Registration only; see {@link Moderation}. Hidden in Discord from members without the permission. */
@SuppressWarnings("unused") // Discovered reflectively by CommandLoader.
public final class Kick extends BunnyCommand {
    public Kick(BunnyHub client) {
        super(client);
        setName("kick");
        setCategory("Moderation");
        setDescription("Remove someone from the server. They can rejoin with an invite.");
        setExample("/kick user:@member reason:Breaking rule 3");
        setDmEnabled(false);
        setMentionEnabled(false);
        addUserPermissions(Permission.KICK_MEMBERS);
        setCooldown(2);
        addOption(Moderation.userOption("Who to kick"));
        addOption(Moderation.reasonOption());
    }

    @Override public void execute(BunnyHub client, CommandContext ctx) { Moderation.run(Moderation.Action.KICK, ctx); }
}
