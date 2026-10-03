package org.bunnys.bunnynexus.commands.timer;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.bunnynexus.timers.Timers;
import org.bunnys.bunnynexus.timers.buttons.SessionMenuManager;
import org.bunnys.bunnynexus.timers.services.PendingSessionManager;
import org.bunnys.bunnynexus.timers.services.SubjectAutocomplete;
import org.bunnys.handler.BunnyHub;
import org.bunnys.utils.Embeds;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class Start extends TimerSubcommand {
    public Start() {
        setName("start");
        setDescription("Start a study session for a course.");
        addOption(new OptionData(OptionType.STRING, "module", "The course to study.", true).setAutoComplete(true));
        addOption(new OptionData(OptionType.STRING, "objective", "Optional objective for this session.", false).setMaxLength(1000));
    }

    @Override
    void run(SlashCommandInteractionEvent event, String userId) {
        String module = string(event, "module");
        String objective = string(event, "objective");
        String channelId = event.getChannel().getId();
        String guildId = event.getGuild() != null ? event.getGuild().getId() : "DM";
        var pendingCard = new Timers(userId, event).buildPendingSessionEmbed(module);

        var hook = event.getHook();
        hook.editOriginalEmbeds(pendingCard)
                .setComponents(ActionRow.of(SessionMenuManager.buildButtons(userId, SessionMenuManager.SessionState.PENDING)))
                .queue(message -> {
                    if (!PendingSessionManager.createPendingSession(userId, module, objective, channelId, guildId, hook, message.getId()))
                        hook.editOriginalEmbeds(Embeds.error("Busy", "Study menus are busy. Please try again shortly."))
                                .setComponents(List.of())
                                .queue(edited -> hook.deleteOriginal().queueAfter(Embeds.ERROR_SECONDS, TimeUnit.SECONDS,
                                        null, ignored -> {}), ignored -> {});
                });
    }

    @Override
    public List<String> autocomplete(BunnyHub client, CommandAutoCompleteInteractionEvent event) {
        if (!event.getFocusedOption().getName().equals("module")) return List.of();
        return SubjectAutocomplete.suggest(event.getUser().getId(), event.getFocusedOption().getValue());
    }
}
