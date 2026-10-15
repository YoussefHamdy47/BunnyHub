package org.bunnys.commands;

import org.bunnys.bunnynexus.info.StatusEmbeds;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;

/** {@code /uptime}: when the bot last started, as Discord timestamps. */
@SuppressWarnings("unused") // Discovered reflectively by CommandLoader.
public final class Uptime extends BunnyCommand {
    public Uptime(BunnyHub client) {
        super(client);
        setName("uptime");
        setCategory("Information");
        setDescription("Show how long the bot has been online.");
        setCooldown(3);
    }

    @Override public void execute(BunnyHub client, CommandContext ctx) {
        ctx.reply(StatusEmbeds.uptime(client.getStartTime()));
    }
}
