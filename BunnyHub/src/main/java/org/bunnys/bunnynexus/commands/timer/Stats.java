package org.bunnys.bunnynexus.commands.timer;

import org.bunnys.handler.utils.InteractionErrors;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.bunnynexus.timers.Timers;
import org.bunnys.utils.AppDesign;

public final class Stats extends BunnySubcommand {
    public Stats() {
        setName("stats");
        setDescription("View your complete study statistics, streaks, and progress.");
        addOption(new OptionData(OptionType.BOOLEAN, "ephemeral", "Hide this from others? (Default: False)", false));
    }

    @Override
    public void execute(BunnyHub client, org.bunnys.handler.commands.context.CommandContext context) {
        var event = ((org.bunnys.handler.commands.context.SlashContext) context).event();
        String userId = event.getUser().getId();
        OptionMapping ephemeralOpt = event.getOption("ephemeral");
        boolean isEphemeral = ephemeralOpt != null && ephemeralOpt.getAsBoolean();

        try {
            Timers timerSystem = new Timers(userId, event);
            MessageEmbed responseEmbed = timerSystem.statDisplay();
            event.getHook().editOriginalEmbeds(responseEmbed).queue();

        } catch (IllegalStateException | IllegalArgumentException e) {
            event.getHook().editOriginal("> " + AppDesign.Emojis.ERROR + " **Action failed:** " + InteractionErrors.userMessage(e))
                    .queue();
        }
    }
}
