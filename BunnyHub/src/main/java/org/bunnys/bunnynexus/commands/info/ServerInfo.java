package org.bunnys.bunnynexus.commands.info;

import org.bunnys.bunnynexus.info.InfoEmbeds;
import org.bunnys.bunnynexus.info.ServerInfoEmbeds;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.context.CommandContext;

public final class ServerInfo extends BunnySubcommand {
    public ServerInfo() {
        setName("server");
        setDescription("Show details about this server");
        addAliases("guild");
        setExample("/info server");
    }

    @Override
    public void execute(BunnyHub client, CommandContext ctx) {
        // CommandGate gates whole commands, so this branch guards itself rather
        // than forcing /info user to be guild-only alongside it.
        if (!ctx.isFromGuild() || ctx.getGuild().isDetached()) {
            ctx.replyTransient(InfoEmbeds.guildOnly());
            return;
        }

        // The owner renders as a raw mention rather than through getOwner(),
        // which needs a member cache this bot deliberately does not keep.
        ctx.defer();
        ctx.reply(ServerInfoEmbeds.serverInfo(ctx.getGuild()));
    }
}
