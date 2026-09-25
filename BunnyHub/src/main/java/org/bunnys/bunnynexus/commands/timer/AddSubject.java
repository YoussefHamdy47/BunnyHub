package org.bunnys.bunnynexus.commands.timer;

import org.bunnys.handler.utils.InteractionErrors;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.database.models.timers.Subject;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.bunnynexus.timers.Timers;
import org.bunnys.utils.AppDesign;
import java.util.Objects;

public final class AddSubject extends BunnySubcommand {
    public AddSubject() {
        setName("add-subject");
        setDescription("Add a course to your semester or academic record.");

        addOption(new OptionData(OptionType.STRING, "destination", "Where should this course be added?", true)
                .addChoice("Current Semester", "SEMESTER")
                .addChoice("Academic Record", "ACCOUNT"));

        addOption(new OptionData(OptionType.STRING, "code", "Course code (e.g., ECES201)", true).setRequiredLength(1, 24));
        addOption(new OptionData(OptionType.STRING, "name", "Course name (e.g., Digital and Analog Electronics)", true).setRequiredLength(1, 70));
        addOption(new OptionData(OptionType.INTEGER, "credits", "Credit hours.", true).setRequiredRange(1, 30));

        addOption(new OptionData(OptionType.STRING, "grade", "Final letter grade. Only used for Academic Record.", false)
                .addChoice("A+", "A+")
                .addChoice("A", "A")
                .addChoice("A-", "A-")
                .addChoice("B+", "B+")
                .addChoice("B", "B")
                .addChoice("B-", "B-")
                .addChoice("C+", "C+")
                .addChoice("C", "C")
                .addChoice("C-", "C-")
                .addChoice("D+", "D+")
                .addChoice("D", "D")
                .addChoice("F", "F"));
    }

    @Override
    public void execute(BunnyHub client, org.bunnys.handler.commands.context.CommandContext context) {
        var event = ((org.bunnys.handler.commands.context.SlashContext) context).event();
        String userId = event.getUser().getId();
        String destStr = Objects.requireNonNull(event.getOption("destination")).getAsString();
        String code = Objects.requireNonNull(event.getOption("code")).getAsString();
        String name = Objects.requireNonNull(event.getOption("name")).getAsString();
        int credits = Objects.requireNonNull(event.getOption("credits")).getAsInt();

        OptionMapping gradeOpt = event.getOption("grade");
        String grade = gradeOpt != null ? gradeOpt.getAsString() : null;

        Timers.RecordDestination destination = Timers.RecordDestination.valueOf(destStr);

        Subject subject = new Subject();
        subject.setSubjectCode(code);
        subject.setSubjectName(name);
        subject.setCreditHours(credits);

        if (destination == Timers.RecordDestination.ACCOUNT && grade != null)
            subject.setGrade(grade);

        try {
            Timers timerSystem = new Timers(userId, event);
            MessageEmbed responseEmbed = timerSystem.addSubject(destination, subject);
            event.getHook().editOriginalEmbeds(responseEmbed).queue();

        } catch (IllegalStateException | IllegalArgumentException e) {
            event.getHook().editOriginal("> " + AppDesign.Emojis.ERROR + " **Action failed:** " + InteractionErrors.userMessage(e))
                    .queue();
        }
    }
}
