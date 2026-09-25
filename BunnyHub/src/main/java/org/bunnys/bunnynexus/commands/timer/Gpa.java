package org.bunnys.bunnynexus.commands.timer;

import org.bunnys.handler.utils.InteractionErrors;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.bunnynexus.timers.buttons.GPAPaginator;
import org.bunnys.bunnynexus.timers.Timers;
import org.bunnys.utils.AppDesign;
import java.util.List;

public final class Gpa extends BunnySubcommand {
    public Gpa() {
        setName("gpa");
        setDescription("View your academic record and cumulative GPA.");
        addOption(new OptionData(OptionType.BOOLEAN, "ephemeral", "Hide this from others? (Default: True)", false));
    }

    @Override
    public void execute(BunnyHub client, org.bunnys.handler.commands.context.CommandContext context) {
        var event = ((org.bunnys.handler.commands.context.SlashContext) context).event();
        String userId = event.getUser().getId();
        OptionMapping ephemeralOpt = event.getOption("ephemeral");
        boolean isEphemeral = ephemeralOpt == null || ephemeralOpt.getAsBoolean();

        try {
            Timers timerSystem = new Timers(userId, event);
            List<MessageEmbed> pages = timerSystem.buildGPAMenu();

            if (pages.isEmpty()) {
                event.getHook().editOriginal("> " + AppDesign.Emojis.ERROR + " **No GPA pages could be generated.**")
                        .queue();
                return;
            }

            String sessionId = GPAPaginator.createSession(userId, pages);

            event.getHook().editOriginalEmbeds(pages.getFirst())
                    .setComponents(net.dv8tion.jda.api.components.actionrow.ActionRow.of(GPAPaginator.buildButtons(sessionId, 0, pages.size())))
                    
                    .queue(
                            message -> GPAPaginator.attachHook(sessionId, event.getHook()),
                            failure -> {
                                GPAPaginator.discardSession(sessionId);
                                event.getHook()
                                    .sendMessage("> " + AppDesign.Emojis.ERROR + " **Failed to open GPA menu.**")
                                    .queue();
                            }
                    );

        } catch (IllegalStateException | IllegalArgumentException e) {
            event.getHook().editOriginal("> " + AppDesign.Emojis.ERROR + " **Action failed:** " + InteractionErrors.userMessage(e))
                    .queue();
        }
    }
}
