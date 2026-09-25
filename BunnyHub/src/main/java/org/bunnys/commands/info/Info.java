package org.bunnys.commands.info;

import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;

/**
 * {@code /info} - lookups for the two things a member usually wants to check: a person,
 * or the server they are both standing in.
 *
 * <p>Kept as one command with two branches rather than two commands. They answer the
 * same question about different subjects, and pairing them means the help menu carries
 * one entry a reader has to find instead of two.
 */
public class Info extends BunnyCommand {

    public Info(BunnyHub client) {
        super(client);
        setName("info");
        setDescription("Look up a user or this server");
        addAliases("whois", "about");
        setCategory("Information");
        setCooldown(5);
        setDeferBeforeDispatch(true);

        addSubcommand(new org.bunnys.bunnynexus.commands.info.UserInfo());

        addSubcommand(new org.bunnys.bunnynexus.commands.info.ServerInfo());
    }
    @Override public boolean defaultEphemeral(CommandContext ctx) {
        return !ctx.isFromGuild();
    }

}
