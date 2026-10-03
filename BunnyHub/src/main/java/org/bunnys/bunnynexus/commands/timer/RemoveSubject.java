package org.bunnys.bunnynexus.commands.timer;

import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.bunnynexus.timers.Timers;
import org.bunnys.bunnynexus.timers.services.SubjectTopics;
import org.bunnys.handler.BunnyHub;
import java.util.List;

public final class RemoveSubject extends TimerSubcommand {
    public RemoveSubject() {
        setName("remove-subject");
        setDescription("Remove a course from your semester or academic record.");
        addOption(Destinations.option("Where should this course be removed from?"));
        addOption(new OptionData(OptionType.STRING, "code", "Course code (e.g., ECES201)", true).setRequiredLength(1, 100)
                .setAutoComplete(true));
    }

    @Override
    void run(SlashCommandInteractionEvent event, String userId) {
        // Accepts both a bare code and an autocomplete choice such as "ECES201 - Electronics".
        String code = SubjectTopics.code(string(event, "code"));
        event.getHook().editOriginalEmbeds(new Timers(userId, event).removeSubject(Destinations.of(event), code)).queue();
    }

    @Override
    public List<String> autocomplete(BunnyHub client, CommandAutoCompleteInteractionEvent event) {
        return Destinations.courseCodes(event);
    }
}
