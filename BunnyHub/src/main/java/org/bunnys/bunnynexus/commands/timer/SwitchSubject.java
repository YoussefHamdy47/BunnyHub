package org.bunnys.bunnynexus.commands.timer;

import org.bunnys.handler.utils.InteractionErrors;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.database.models.timers.TimerData;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.bunnynexus.timers.Timers;
import org.bunnys.utils.AppDesign;
import java.util.List;
import java.util.Objects;

public final class SwitchSubject extends BunnySubcommand {
    public SwitchSubject() {
        setName("switch");
        setDescription("Seamlessly switch your active study module without stopping the timer.");
        addOption(new OptionData(OptionType.STRING, "module", "The new course to study.", true)
                .setAutoComplete(true));
    }

    @Override
    public void execute(BunnyHub client, org.bunnys.handler.commands.context.CommandContext context) {
        var event = ((org.bunnys.handler.commands.context.SlashContext) context).event();
        String userId = event.getUser().getId();
        String moduleSelection = Objects.requireNonNull(event.getOption("module")).getAsString();

        if (moduleSelection.contains("No subjects found")) {
            event.getHook().editOriginal("> " + AppDesign.Emojis.ERROR + " **Action failed:** You must add a course first.")
                    .queue();
            return;
        }

        try {
            Timers timerSystem = new Timers(userId, event);
            MessageEmbed responseEmbed = timerSystem.changeSubject(moduleSelection);
            event.getHook().editOriginalEmbeds(responseEmbed).queue();

        } catch (IllegalStateException | IllegalArgumentException e) {
            event.getHook().editOriginal("> " + AppDesign.Emojis.ERROR + " **Action failed:** " + InteractionErrors.userMessage(e))
                    .queue();
        }
    }
    @Override
    public java.util.List<String> autocomplete(org.bunnys.handler.BunnyHub client, net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent event) {
        if (!event.getFocusedOption().getName().equals("module")) return List.of();
        return org.bunnys.bunnynexus.timers.services.SubjectAutocomplete.suggest(
                event.getUser().getId(), event.getFocusedOption().getValue());
    }
}