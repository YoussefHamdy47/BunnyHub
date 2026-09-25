package org.bunnys.bunnynexus.commands.alerts;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

public final class FreebieSetup extends FreebieSubcommand {
    public FreebieSetup(FreebieActions actions) {
        super(actions, "setup", "Add or replace a channel's free-game store settings.");
        revision();
        addOption(text("channel", "Destination channel ID in this server", true, 20));
        addOption(text("store", "Exact supported store identifier", true, 256));
        addOption(text("market", "Explicit supported market identifier", true, 256));
        addOption(text("roles", "Comma-separated role IDs; type none for no pings (replaces existing roles)", true, 2200));
        addOption(new OptionData(OptionType.BOOLEAN, "enabled", "Enable this subscription for new observations", true));
    }
}

