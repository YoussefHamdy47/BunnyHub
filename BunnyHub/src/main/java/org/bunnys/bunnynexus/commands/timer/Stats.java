package org.bunnys.bunnynexus.commands.timer;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.bunnynexus.timers.Timers;

public final class Stats extends TimerSubcommand {
    public Stats() {
        setName("stats");
        setDescription("View your complete study statistics, streaks, and progress.");
        // Read by the handler when it defers the reply.
        addOption(new OptionData(OptionType.BOOLEAN, "ephemeral", "Hide this from others? (Default: False)", false));
    }

    @Override
    void run(SlashCommandInteractionEvent event, String userId) {
        event.getHook().editOriginalEmbeds(new Timers(userId, event).statDisplay()).queue();
    }
}
