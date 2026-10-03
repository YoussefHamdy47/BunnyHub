package org.bunnys.commands.freebies;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.bunnynexus.commands.freebies.FreeGamesCommand;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;

/** Registration only; see {@link FreeGamesCommand}. Open to everyone, unlike the Manage Server {@code /freebie}. */
@SuppressWarnings("unused") // Discovered reflectively by CommandLoader.
public final class FreeGames extends BunnyCommand {
    private final FreeGamesCommand implementation = new FreeGamesCommand();

    public FreeGames(BunnyHub client) {
        super(client);
        setName("free-games");
        setCategory("Free Games");
        setDescription("See the games you can claim for free right now.");
        addAliases("freegames", "free");
        setDeferBeforeDispatch(true);
        setCooldown(5);
        setExample("/free-games launcher:epic");
        var launcher = new OptionData(OptionType.STRING, "launcher", "Only show one launcher", false);
        for (FreebieStore store : FreebieStore.values()) launcher.addChoice(store.label(), store.id());
        addOption(launcher);
    }

    @Override public void execute(BunnyHub client, CommandContext context) {
        implementation.execute(client, context);
    }
}
