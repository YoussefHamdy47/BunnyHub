package org.bunnys.bunnynexus.commands.timer;

import org.bunnys.handler.utils.InteractionErrors;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.bunnynexus.timers.Timers;
import org.bunnys.utils.AppDesign;
import java.util.Objects;

public final class Register extends BunnySubcommand {
    public Register() {
        setName("register");
        setDescription("Create a new semester and start tracking your study time.");
        addOption(new OptionData(OptionType.STRING, "semester", "The name of the semester", true).setRequiredLength(1, 80));
    }

    @Override
    public void execute(BunnyHub client, org.bunnys.handler.commands.context.CommandContext context) {
        var event = ((org.bunnys.handler.commands.context.SlashContext) context).event();
        String userId = event.getUser().getId();
        String semesterName = Objects.requireNonNull(event.getOption("semester")).getAsString();

        try {
            Timers timerSystem = new Timers(userId, event);
            MessageEmbed responseEmbed = timerSystem.register(semesterName);
            event.getHook().editOriginalEmbeds(responseEmbed).queue();

        } catch (IllegalStateException | IllegalArgumentException e) {
            event.getHook().editOriginal("> " + AppDesign.Emojis.ERROR + " **Action failed:** " + InteractionErrors.userMessage(e))
                    .queue();
        }
    }
}
