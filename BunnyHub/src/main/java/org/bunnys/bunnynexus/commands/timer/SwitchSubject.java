package org.bunnys.bunnynexus.commands.timer;

import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.bunnynexus.timers.Timers;
import org.bunnys.bunnynexus.timers.services.SubjectAutocomplete;
import org.bunnys.handler.BunnyHub;
import java.util.List;

public final class SwitchSubject extends TimerSubcommand {
    public SwitchSubject() {
        setName("switch");
        setDescription("Seamlessly switch your active study module without stopping the timer.");
        addOption(new OptionData(OptionType.STRING, "module", "The new course to study.", true).setAutoComplete(true));
    }

    @Override
    void run(SlashCommandInteractionEvent event, String userId) {
        event.getHook().editOriginalEmbeds(new Timers(userId, event).changeSubject(string(event, "module"))).queue();
    }

    @Override
    public List<String> autocomplete(BunnyHub client, CommandAutoCompleteInteractionEvent event) {
        if (!event.getFocusedOption().getName().equals("module")) return List.of();
        return SubjectAutocomplete.suggest(event.getUser().getId(), event.getFocusedOption().getValue());
    }
}
