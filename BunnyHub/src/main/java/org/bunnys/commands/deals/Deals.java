package org.bunnys.commands.deals;

import net.dv8tion.jda.api.interactions.IntegrationType;
import org.bunnys.bunnynexus.commands.deals.DealsCommand;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;

/**
 * Registration only; see {@link DealsCommand}. Good deals on paid PC games, separate from free games.
 * {@code @Bot deals} lists them: a bare mention falls through to {@code list}.
 */
@SuppressWarnings("unused") // Discovered reflectively by CommandLoader.
public final class Deals extends BunnyCommand {
    public Deals(BunnyHub client) {
        super(client);
        setName("deals");
        setInstallTypes(IntegrationType.GUILD_INSTALL, IntegrationType.USER_INSTALL);
        setCategory("Free Games");
        setDescription("Big discounts on well-reviewed PC and console games, updated every few hours.");
        addAliases("deal", "offers");
        setDeferBeforeDispatch(true);
        setCooldown(5);
        setExample("/deals store:steam");
        addSubcommand(new DealsCommand.ListDeals());
        setDefaultSubcommand("list");
    }

}
