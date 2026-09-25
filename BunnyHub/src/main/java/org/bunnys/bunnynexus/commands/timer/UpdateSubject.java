package org.bunnys.bunnynexus.commands.timer;

import org.bunnys.handler.utils.InteractionErrors;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.database.models.timers.Grade;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.bunnynexus.timers.services.TimerSubjectService;
import org.bunnys.utils.AppDesign;
import java.util.List;

public final class UpdateSubject extends BunnySubcommand {
    public UpdateSubject() {
        setName("update-subject");
        setDescription("Edit a course or replace its grade after a retake, preserving study history.");
        addOption(new OptionData(OptionType.STRING, "destination", "Which record should be updated?", true)
                .addChoice("Current Semester", "SEMESTER").addChoice("Academic Record", "ACCOUNT"));
        addOption(new OptionData(OptionType.STRING, "code", "Existing course code", true).setAutoComplete(true));
        addOption(new OptionData(OptionType.STRING, "new-code", "Replacement course code", false).setRequiredLength(1, 24));
        addOption(new OptionData(OptionType.STRING, "name", "Updated course name", false).setRequiredLength(1, 70));
        addOption(new OptionData(OptionType.INTEGER, "credits", "Updated credit hours", false).setRequiredRange(1, 30));
        OptionData grade = new OptionData(OptionType.STRING, "grade", "Replacement grade (including retakes)", false);
        for (Grade value : Grade.values()) grade.addChoice(value.getStringValue(), value.getStringValue());
        addOption(grade);
        addOption(new OptionData(OptionType.INTEGER, "marks-lost", "Updated marks lost", false).setRequiredRange(0, Integer.MAX_VALUE));
        addOption(new OptionData(OptionType.BOOLEAN, "clear-grade", "Remove the current grade", false));
    }

    @Override public List<String> autocomplete(BunnyHub client, CommandAutoCompleteInteractionEvent event) {
        var destination = event.getOption("destination");
        return TimerSubjectService.subjectCodes(event.getUser().getId(),
                destination != null && "ACCOUNT".equals(destination.getAsString()));
    }

    @Override public void execute(BunnyHub client, org.bunnys.handler.commands.context.CommandContext context) {
                var event = ((org.bunnys.handler.commands.context.SlashContext) context).event();
        try {
            boolean account = "ACCOUNT".equals(string(event, "destination"));
            var clear = event.getOption("clear-grade");
            var subject = TimerSubjectService.updateSubject(event.getUser().getId(), account, string(event, "code"),
                    new TimerSubjectService.SubjectUpdate(string(event, "new-code"), string(event, "name"),
                            integer(event, "credits"), string(event, "grade"), integer(event, "marks-lost"),
                            clear != null && clear.getAsBoolean()));
            var embed = new EmbedBuilder().setColor(AppDesign.ColorCodes.CYAN).setTitle("Course updated")
                    .setDescription("**" + subject.getSubjectCode() + " — " + subject.getSubjectName() + "**")
                    .addField("Credit hours", String.valueOf(subject.getCreditHours()), true)
                    .addField("Grade", subject.getGrade() == null ? "Not graded" : subject.getGrade(), true)
                    .addField("Marks lost", subject.getMarksLost() == null ? "Not recorded" : subject.getMarksLost().toString(), true)
                    .setFooter(account ? "Academic record updated • GPA uses the current grade and credits"
                            : "Current semester updated • Academic record unchanged");
            event.getHook().editOriginalEmbeds(embed.build()).queue();
        } catch (IllegalArgumentException | IllegalStateException error) {
            event.getHook().editOriginal("Action failed: " + InteractionErrors.userMessage(error)).queue();
        }
    }

    private static String string(SlashCommandInteractionEvent event, String name) {
        var option = event.getOption(name);
        return option == null ? null : option.getAsString();
    }
    private static Integer integer(SlashCommandInteractionEvent event, String name) {
        var option = event.getOption(name);
        return option == null ? null : option.getAsInt();
    }
}
