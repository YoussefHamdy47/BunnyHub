package org.bunnys.bunnynexus.commands.timer;

import org.bunnys.handler.utils.InteractionErrors;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.database.models.timers.TimerData;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.bunnynexus.timers.Timers;
import org.bunnys.bunnynexus.timers.buttons.SessionMenuManager;
import org.bunnys.bunnynexus.timers.services.PendingSessionManager;
import org.bunnys.utils.AppDesign;
import java.util.List;
import java.util.Objects;

public final class Start extends BunnySubcommand {
    public Start() {
        setName("start");
        setDescription("Start a study session for a course.");

        addOption(new OptionData(OptionType.STRING, "module", "The course to study.", true)
                .setAutoComplete(true));

        addOption(new OptionData(OptionType.STRING, "objective", "Optional objective for this session.", false).setMaxLength(1000));
    }

    @Override
    public void execute(BunnyHub client, org.bunnys.handler.commands.context.CommandContext context) {
        var event = ((org.bunnys.handler.commands.context.SlashContext) context).event();
        String userId = event.getUser().getId();
        String moduleSelection = Objects.requireNonNull(event.getOption("module")).getAsString();

        OptionMapping objectiveOpt = event.getOption("objective");
        String objective = objectiveOpt != null ? objectiveOpt.getAsString() : null;

        if (moduleSelection.contains("No subjects found")) {
            event.getHook().editOriginal("> " + AppDesign.Emojis.ERROR + " **Action failed:** You must add a course before starting a study session.")
                    .queue();
            return;
        }

        String channelId = event.getChannel().getId();
        String guildId = event.isFromGuild() ? Objects.requireNonNull(event.getGuild()).getId() : "DM";

        try {
            Timers timerSystem = new Timers(userId, event);
            MessageEmbed pendingEmbed = timerSystem.buildPendingSessionEmbed(moduleSelection);

            event.getHook().editOriginalEmbeds(pendingEmbed)
                    .setComponents(net.dv8tion.jda.api.components.actionrow.ActionRow.of(SessionMenuManager.buildButtons(userId, SessionMenuManager.SessionState.PENDING)))
                    .queue(message -> {
                        if (!PendingSessionManager.createPendingSession(userId, moduleSelection, objective, channelId, guildId, event.getHook(), message.getId()))
                            event.getHook().editOriginal("Study menus are busy. Please try again shortly.")
                                    .setEmbeds(List.of()).setComponents(List.of()).queue(null, ignored -> {});
                    });

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