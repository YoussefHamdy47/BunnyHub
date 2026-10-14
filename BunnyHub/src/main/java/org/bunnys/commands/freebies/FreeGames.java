package org.bunnys.commands.freebies;

import org.bunnys.bunnynexus.commands.freebies.FreeGamesCommand;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.handler.commands.context.SlashContext;

/**
 * Registration only; see {@link FreeGamesCommand}. Everything free-game related lives here: the list, personal DMs
 * and (for server admins) the alert dashboard.
 * {@code @Bot free-games epic} still lists games: a bare mention falls through to {@code list}.
 */
@SuppressWarnings("unused") // Discovered reflectively by CommandLoader.
public final class FreeGames extends BunnyCommand {
    public FreeGames(BunnyHub client) {
        super(client);
        setName("free-games");
        setCategory("Free Games");
        setDescription("Free games: see what's free now, get them by DM, or set up alerts for your server.");
        addAliases("freegames", "free");
        setDeferBeforeDispatch(true);
        setCooldown(5);
        setExample("/free-games list launcher:epic");
        addSubcommand(new FreeGamesCommand.ListGames());
        addSubcommand(new FreeGamesCommand.Dms());
        addSubcommand(new FreeGamesCommand.Setup());
        addSubcommand(new FreeGamesCommand.Help());
        setDefaultSubcommand("list");
    }

    /** DM settings and server setup are private; the game list stays public. */
    @Override public boolean defaultEphemeral(CommandContext context) {
        return context instanceof SlashContext slash && !"list".equals(slash.event().getSubcommandName());
    }
}
