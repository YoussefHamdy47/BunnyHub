package org.bunnys.commands.moderation;

import net.dv8tion.jda.api.Permission;
import org.bunnys.bunnynexus.moderation.Moderation;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;

/** Registration only; see {@link Moderation}. Hidden in Discord from members without the permission. */
@SuppressWarnings("unused") // Discovered reflectively by CommandLoader.
public final class Unmute extends BunnyCommand {
    public Unmute(BunnyHub client) {
        super(client);
        setName("unmute");
        setCategory("Moderation");
        setDescription("End someone's mute early.");
        setExample("/unmute user:@member");
        setDmEnabled(false);
        setMentionEnabled(false);
        addUserPermissions(Permission.MODERATE_MEMBERS);
        setCooldown(2);
        addOption(Moderation.userOption("Who to unmute"));
        addOption(Moderation.reasonOption());
    }

    @Override public void execute(BunnyHub client, CommandContext ctx) { Moderation.run(Moderation.Action.UNMUTE, ctx); }
}
