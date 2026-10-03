package org.bunnys.bunnynexus.commands.timer;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.bunnynexus.timers.Timers;
import org.bunnys.bunnynexus.timers.buttons.GPAPaginator;
import org.bunnys.utils.Embeds;
import java.util.concurrent.TimeUnit;

public final class Gpa extends TimerSubcommand {
    public Gpa() {
        setName("gpa");
        setDescription("View your academic record and cumulative GPA.");
        // Read by the handler when it defers; Timer#defaultEphemeral makes this one private unless asked otherwise.
        addOption(new OptionData(OptionType.BOOLEAN, "ephemeral", "Hide this from others? (Default: True)", false));
    }

    @Override
    void run(SlashCommandInteractionEvent event, String userId) {
        var pages = new Timers(userId, event).buildGPAMenu();
        String sessionId = GPAPaginator.createSession(userId, pages);
        var hook = event.getHook();
        hook.editOriginalEmbeds(pages.getFirst())
                .setComponents(ActionRow.of(GPAPaginator.buildButtons(sessionId, 0, pages.size())))
                .queue(message -> GPAPaginator.attachHook(sessionId, hook), failure -> {
                    GPAPaginator.discardSession(sessionId);
                    hook.sendMessageEmbeds(Embeds.error("GPA menu unavailable", "The menu could not be opened. Please try again."))
                            .queue(sent -> hook.deleteMessageById(sent.getId())
                                    .queueAfter(Embeds.ERROR_SECONDS, TimeUnit.SECONDS, null, ignored -> {}), ignored -> {});
                });
    }
}
