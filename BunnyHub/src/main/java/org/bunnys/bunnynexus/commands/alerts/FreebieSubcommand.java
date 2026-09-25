package org.bunnys.bunnynexus.commands.alerts;

import java.util.List;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.context.*;

abstract class FreebieSubcommand extends BunnySubcommand {
    private final FreebieActions actions;
    FreebieSubcommand(FreebieActions actions, String name, String description) {
        this.actions = actions; setName(name); setDescription(description);
    }
    @Override public final void execute(BunnyHub client, CommandContext context) {
        if (!(context instanceof SlashContext slash)) return;
        slash.event().getHook().editOriginal(actions.run(slash.event(), getName()))
                .setAllowedMentions(List.of()).queue();
    }
    static OptionData text(String name, String description, boolean required, int max) {
        return new OptionData(OptionType.STRING, name, description, required).setRequiredLength(1, max);
    }
    final void revision() {
        addOption(text("revision", "Exact revision shown by /freebie status (0 for first setup)", true, 19));
    }
    final void scope() {
        addOption(new OptionData(OptionType.STRING, "scope", "Which settings should change?", true)
                .addChoice("Entire server", "server").addChoice("Channel", "channel").addChoice("Store in channel", "store"));
    }
    final void target() {
        addOption(text("channel", "Channel ID; accepts deleted channels for stopping/removal", false, 20));
        addOption(text("store", "Exact store identifier, required only for store scope", false, 256));
    }
}
