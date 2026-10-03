package org.bunnys.bunnynexus.commands.timer;

import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.bunnynexus.timers.Timers.RecordDestination;
import org.bunnys.bunnynexus.timers.services.TimerSubjectService;
import java.util.List;

/** The {@code destination} option shared by the course commands: the current semester or the academic record. */
final class Destinations {
    private Destinations() {}

    static OptionData option(String description) {
        return new OptionData(OptionType.STRING, "destination", description, true)
                .addChoice("Current Semester", RecordDestination.SEMESTER.name())
                .addChoice("Academic Record", RecordDestination.ACCOUNT.name());
    }

    static RecordDestination of(SlashCommandInteractionEvent event) {
        var option = event.getOption("destination");
        return option != null && RecordDestination.ACCOUNT.name().equals(option.getAsString())
                ? RecordDestination.ACCOUNT : RecordDestination.SEMESTER;
    }

    /** Course codes from whichever record the user already picked, for {@code code} autocomplete. */
    static List<String> courseCodes(CommandAutoCompleteInteractionEvent event) {
        var option = event.getOption("destination");
        boolean account = option != null && RecordDestination.ACCOUNT.name().equals(option.getAsString());
        return TimerSubjectService.subjectCodes(event.getUser().getId(), account);
    }
}
