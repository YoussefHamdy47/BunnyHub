package org.bunnys.bunnynexus.commands.timer;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.bunnynexus.timers.Timers;
import org.bunnys.database.models.timers.Grade;
import org.bunnys.database.models.timers.Subject;

public final class AddSubject extends TimerSubcommand {
    public AddSubject() {
        setName("add-subject");
        setDescription("Add a course to your semester or academic record.");
        addOption(Destinations.option("Where should this course be added?"));
        addOption(new OptionData(OptionType.STRING, "code", "Course code (e.g., ECES201)", true).setRequiredLength(1, 24));
        addOption(new OptionData(OptionType.STRING, "name", "Course name (e.g., Digital and Analog Electronics)", true).setRequiredLength(1, 70));
        addOption(new OptionData(OptionType.INTEGER, "credits", "Credit hours.", true).setRequiredRange(1, 30));
        var grade = new OptionData(OptionType.STRING, "grade", "Final letter grade. Only used for Academic Record.", false);
        // Withdraw and Pass carry no GPA weight; a finished course is recorded with a letter grade.
        for (Grade value : Grade.values())
            if (value != Grade.W && value != Grade.P) grade.addChoice(value.getStringValue(), value.getStringValue());
        addOption(grade);
    }

    @Override
    void run(SlashCommandInteractionEvent event, String userId) {
        var destination = Destinations.of(event);
        Subject subject = new Subject();
        subject.setSubjectCode(string(event, "code"));
        subject.setSubjectName(string(event, "name"));
        subject.setCreditHours(integer(event, "credits"));
        String grade = string(event, "grade");
        if (destination == Timers.RecordDestination.ACCOUNT && grade != null) subject.setGrade(grade);
        event.getHook().editOriginalEmbeds(new Timers(userId, event).addSubject(destination, subject)).queue();
    }
}
