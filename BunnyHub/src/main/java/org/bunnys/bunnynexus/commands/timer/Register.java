package org.bunnys.bunnynexus.commands.timer;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.bunnynexus.timers.Timers;

public final class Register extends TimerSubcommand {
    public Register() {
        setName("register");
        setDescription("Create a new semester and start tracking your study time.");
        addOption(new OptionData(OptionType.STRING, "semester", "The name of the semester", true).setRequiredLength(1, 80));
    }

    @Override
    void run(SlashCommandInteractionEvent event, String userId) {
        event.getHook().editOriginalEmbeds(new Timers(userId, event).register(string(event, "semester"))).queue();
    }
}
