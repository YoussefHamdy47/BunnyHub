package org.bunnys.commands.moderation;

import net.dv8tion.jda.api.Permission;
import org.bunnys.bunnynexus.moderation.Moderation;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;

/** Registration only; see {@link Moderation}. Hidden in Discord from members without the permission. */
@SuppressWarnings("unused") // Discovered reflectively by CommandLoader.
public final class Mute extends BunnyCommand {
    public Mute(BunnyHub client) {
        super(client);
        setName("mute");
        setCategory("Moderation");
        setDescription("Mute someone with a Discord timeout: no chatting, reacting or voice until it ends.");
        setExample("/mute user:@member duration:1 hour reason:Spamming");
        setDmEnabled(false);
        setMentionEnabled(false);
        addUserPermissions(Permission.MODERATE_MEMBERS);
        setCooldown(2);
        addOption(Moderation.userOption("Who to mute"));
        addOption(Moderation.durationOption());
        addOption(Moderation.reasonOption());
    }

    @Override public void execute(BunnyHub client, CommandContext ctx) { Moderation.run(Moderation.Action.MUTE, ctx); }
}
