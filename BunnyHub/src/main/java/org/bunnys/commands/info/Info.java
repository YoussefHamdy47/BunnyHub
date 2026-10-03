package org.bunnys.commands.info;

import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.bunnynexus.commands.info.ServerInfo;
import org.bunnys.bunnynexus.commands.info.UserInfo;

/**
 * {@code /info} - lookups for the two things a member usually wants to check: a person,
 * or the server they are both standing in.
 *
 * <p>Kept as one command with two branches rather than two commands. They answer the
 * same question about different subjects, and pairing them means the help menu carries
 * one entry a reader has to find instead of two.
 */
@SuppressWarnings("unused") // Discovered reflectively by CommandLoader.
public final class Info extends BunnyCommand {

    public Info(BunnyHub client) {
        super(client);
        setName("info");
        setDescription("Look up a user or this server");
        addAliases("whois", "about");
        setCategory("Information");
        setCooldown(5);
        setDeferBeforeDispatch(true);

        addSubcommand(new UserInfo());

        addSubcommand(new ServerInfo());
    }
    @Override public boolean defaultEphemeral(CommandContext ctx) {
        return !ctx.isFromGuild();
    }

}
