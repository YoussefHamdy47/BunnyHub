package org.bunnys.buttons;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.bunnynexus.timers.Timers;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.buttons.BunnyButton;
import org.bunnys.handler.utils.InteractionErrors;
import org.bunnys.utils.AppDesign;
import org.bunnys.utils.Embeds;
import org.bunnys.utils.Replies;
import java.util.List;

/** Confirm / cancel on the {@code /timer end_semester} warning: {@code semester_end_btn:<action>:<owner>[:<revision>]}. */
@SuppressWarnings("unused") // Discovered reflectively by ButtonRouter.
public class SemesterEndButtons extends BunnyButton {
    @Override
    public String getPrefix() {
        return "semester_end_btn";
    }

    @Override
    public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        if (args.length < 3) return;
        String action = args[1], ownerId = args[2];
        if (!event.getUser().getId().equals(ownerId)) {
            Replies.error(event, "Access denied", "This prompt belongs to someone else.");
            return;
        }
        try {
            List<Button> disabled = event.getMessage().getComponentTree().findAll(Button.class).stream()
                    .map(Button::asDisabled).toList();
            if (action.equals("cancel")) {
                event.editMessageEmbeds(Embeds.of(AppDesign.Emojis.VERIFY, "Semester kept",
                                "Nothing was archived. Your semester and its telemetry stay active.").build())
                        .setComponents(ActionRow.of(disabled)).queue();
            } else if (action.equals("confirm")) {
                if (args.length < 4) {
                    Replies.error(event, "Expired prompt", "Run `/timer end_semester` again.");
                    return;
                }
                // A modal must be the first answer to the click; the buttons are locked right after.
                event.replyModal(new Timers(ownerId, event).buildEndSemesterModal(args[3])).queue();
                event.getMessage().editMessageComponents(ActionRow.of(disabled)).queue();
            }
        } catch (RuntimeException e) {
            InteractionErrors.report(event, e);
        }
    }
}
