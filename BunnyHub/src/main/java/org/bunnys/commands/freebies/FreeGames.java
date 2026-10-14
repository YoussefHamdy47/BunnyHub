package org.bunnys.commands.freebies;

import org.bunnys.bunnynexus.commands.freebies.FreeGamesCommand;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.handler.commands.context.SlashContext;

/**
 * Registration only; see {@link FreeGamesCommand}. Open to everyone, unlike the Manage Server {@code /freebie}.
 * {@code @Bot free-games epic} still lists games: a bare mention falls through to {@code list}.
 */
@SuppressWarnings("unused") // Discovered reflectively by CommandLoader.
public final class FreeGames extends BunnyCommand {
    public FreeGames(BunnyHub client) {
        super(client);
        setName("free-games");
        setCategory("Free Games");
        setDescription("See the games you can claim for free right now, or get them by DM.");
        addAliases("freegames", "free");
        setDeferBeforeDispatch(true);
        setCooldown(5);
        setExample("/free-games list launcher:epic");
        addSubcommand(new FreeGamesCommand.ListGames());
        addSubcommand(new FreeGamesCommand.Subscribe());
        addSubcommand(new FreeGamesCommand.Unsubscribe());
        setDefaultSubcommand("list");
    }

    /** DM settings are personal, so they are private; the game list stays public. */
    @Override public boolean defaultEphemeral(CommandContext context) {
        return context instanceof SlashContext slash && !"list".equals(slash.event().getSubcommandName());
    }
}
