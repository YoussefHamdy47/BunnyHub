package org.bunnys.buttons;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.buttons.BunnyButton;
import org.bunnys.bunnynexus.timers.Timers;
import org.bunnys.utils.AppDesign;

import java.util.List;
import java.util.stream.Collectors;

public class SemesterEndButtons extends BunnyButton {

    @Override
    public String getPrefix() {
        return "semester_end_btn";
    }

    @Override
    public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        if (args.length < 3) return;

        String action = args[1];
        String targetId = args[2];

        if (!event.getUser().getId().equals(targetId)) {
            event.reply("> " + AppDesign.Emojis.ERROR + " **Access Denied:** This prompt belongs to someone else.")
                    .setEphemeral(true).queue();
            return;
        }

        try {
            Timers timerSystem = new Timers(targetId, event);

            // Dynamically fetch and disable all buttons on the current message
            List<Button> disabledButtons = event.getMessage().getComponentTree().findAll(Button.class).stream()
                    .map(Button::asDisabled)
                    .collect(Collectors.toList());

            if (action.equals("cancel")) {
                EmbedBuilder cancelEmbed = new EmbedBuilder()
                        .setColor(AppDesign.ColorCodes.CYAN)
                        .setDescription("> *Semester termination cancelled. Telemetry remains active.*");

                // Overwrites the red warning and injects the greyed-out buttons
                event.editMessageEmbeds(cancelEmbed.build())
                        .setComponents(net.dv8tion.jda.api.components.actionrow.ActionRow.of(disabledButtons))
                        .queue();
            }
            else if (action.equals("confirm")) {
                if (args.length < 4) {
                    event.reply("> " + AppDesign.Emojis.ERROR + " **Expired prompt:** Run `/timer end_semester` again.")
                            .setEphemeral(true).queue();
                    return;
                }
                // 1. You MUST reply with the modal first to satisfy the interaction requirement
                event.replyModal(timerSystem.buildEndSemesterModal(args[3])).queue();

                // 2. Immediately edit the original message via REST API to lock the buttons
                event.getMessage().editMessageComponents(ActionRow.of(disabledButtons)).queue();
            }
        } catch (Exception e) {
            org.bunnys.handler.utils.InteractionErrors.report(event, e);
        }
    }
}
