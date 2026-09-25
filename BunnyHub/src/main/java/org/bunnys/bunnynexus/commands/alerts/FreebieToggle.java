package org.bunnys.bunnynexus.commands.alerts;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

public final class FreebieToggle extends FreebieSubcommand {
    public FreebieToggle(FreebieActions actions) {
        super(actions, "toggle", "Enable or disable server, channel or store settings.");
        revision(); scope();
        addOption(new OptionData(OptionType.BOOLEAN, "enabled", "Whether the selected scope is enabled", true));
        target();
    }
}
