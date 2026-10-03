package org.bunnys.buttons;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.bunnynexus.timers.buttons.LongSessionPrompt;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.buttons.BunnyButton;
import org.bunnys.handler.utils.InteractionErrors;
import org.bunnys.utils.Embeds;
import org.bunnys.utils.Replies;
import java.util.OptionalDouble;

/**
 * The long-session prompt: keep the tracked time, open the correction form, or keep the session running. Not
 * deferred by the router, because "Enter my real time" must answer with a form; the others acknowledge first.
 */
@SuppressWarnings("unused") // Discovered reflectively by ButtonRouter.
public class LongSessionButtons extends BunnyButton {
    @Override public String getPrefix() { return LongSessionPrompt.BUTTON_PREFIX; }
    @Override public long cooldownMillis() { return ECONOMY_COOLDOWN; }

    @Override
    public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        if (args.length < 4 || !event.getUser().getId().equals(args[2])) {
            Replies.error(event, "Access denied", "This study session belongs to someone else.");
            return;
        }
        switch (args[1]) {
            case LongSessionPrompt.KEEP -> {
                long sessionStart;
                try { sessionStart = Long.parseLong(args[3]); }
                catch (NumberFormatException malformed) { Replies.error(event, "Expired control", "End the session again."); return; }
                event.deferEdit().complete();
                try {
                    LongSessionPrompt.finish(event, args[2], sessionStart, OptionalDouble.empty());
                } catch (InteractionErrors.InputFailure | InteractionErrors.StateFailure refused) {
                    Replies.error(event, "Session not ended", InteractionErrors.userMessage(refused));
                }
            }
            case LongSessionPrompt.FIX -> LongSessionPrompt.form(args).ifPresentOrElse(form -> event.replyModal(form).queue(),
                    () -> Replies.error(event, "Expired control", "End the session again."));
            case LongSessionPrompt.CANCEL -> event.editMessageEmbeds(Embeds.of("▶️", "Still running",
                    "Your session keeps going. End it whenever you're done.").build()).setComponents().queue();
            default -> Replies.error(event, "Expired control", "End the session again.");
        }
    }
}
