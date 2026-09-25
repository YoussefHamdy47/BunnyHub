package org.bunnys.bunnynexus.commands.alerts;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

public final class FreebieStatus extends FreebieSubcommand {
    public FreebieStatus(FreebieActions actions) {
        super(actions, "status", "View current settings and the revision required for edits.");
        addOption(new OptionData(OptionType.INTEGER, "page", "Settings page", false).setMinValue(1).setMaxValue(4100));
    }
}

