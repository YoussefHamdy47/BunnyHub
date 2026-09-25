package org.bunnys.bunnynexus.commands.timer;

import org.bunnys.handler.utils.InteractionErrors;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.bunnynexus.timers.Timers;
import org.bunnys.utils.AppDesign;
import java.util.List;
import java.util.Objects;

public final class RemoveSubject extends BunnySubcommand {
    public RemoveSubject() {
        setName("remove-subject");
        setDescription("Remove a course from your semester or academic record.");

        addOption(new OptionData(OptionType.STRING, "destination", "Where should this course be removed from?", true)
                .addChoice("Current Semester", "SEMESTER")
                .addChoice("Academic Record", "ACCOUNT"));
        addOption(new OptionData(OptionType.STRING, "code", "Course code (e.g., ECES201)", true).setRequiredLength(1, 100)
                .setAutoComplete(true));
    }

    @Override
    public void execute(BunnyHub client, org.bunnys.handler.commands.context.CommandContext context) {
        var event = ((org.bunnys.handler.commands.context.SlashContext) context).event();
        String userId = event.getUser().getId();
        String destStr = Objects.requireNonNull(event.getOption("destination")).getAsString();
        String rawCode = Objects.requireNonNull(event.getOption("code")).getAsString();

        String code = org.bunnys.bunnynexus.timers.services.SubjectTopics.code(rawCode);
        Timers.RecordDestination destination = Timers.RecordDestination.valueOf(destStr);

        try {
            Timers timerSystem = new Timers(userId, event);
            MessageEmbed responseEmbed = timerSystem.removeSubject(destination, code);
            event.getHook().editOriginalEmbeds(responseEmbed).queue();

        } catch (IllegalStateException | IllegalArgumentException e) {
            event.getHook().editOriginal("> " + AppDesign.Emojis.ERROR + " **Action failed:** " + InteractionErrors.userMessage(e))
                    .queue();
        }
    }

    @Override
    public List<String> autocomplete(BunnyHub client,
            net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent event) {
        var destination = event.getOption("destination");
        return org.bunnys.bunnynexus.timers.services.TimerSubjectService.subjectCodes(event.getUser().getId(),
                destination != null && "ACCOUNT".equals(destination.getAsString()));
    }
}
