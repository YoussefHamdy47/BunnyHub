package org.bunnys.modals;

import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import org.bunnys.bunnynexus.timers.buttons.LongSessionPrompt;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.modals.BunnyModal;
import org.bunnys.handler.utils.InteractionErrors;
import org.bunnys.utils.Replies;
import java.util.OptionalDouble;

/**
 * The real study time for a long session: {@code session_long_time:<owner>:<sessionStart>}. The service checks the
 * entry against the live session (at least what subject switches already logged, at most what the timer tracked).
 */
@SuppressWarnings("unused") // Discovered reflectively by ModalRouter.
public class LongSessionModal extends BunnyModal {
    @Override public String getPrefix() { return LongSessionPrompt.MODAL_PREFIX; }
    @Override public boolean deferEditBeforeDispatch() { return true; }

    @Override
    public void execute(BunnyHub client, ModalInteractionEvent event, String[] args) {
        if (args.length != 3 || !event.getUser().getId().equals(args[1])) {
            Replies.error(event, "Access denied", "This study session belongs to someone else.");
            return;
        }
        try {
            long sessionStart = Long.parseLong(args[2]);
            var hours = event.getValue(LongSessionPrompt.HOURS);
            var minutes = event.getValue(LongSessionPrompt.MINUTES);
            if (hours == null) throw new InteractionErrors.InputFailure("Enter how many hours you studied.");
            double reported = LongSessionPrompt.parse(hours.getAsString(), minutes == null ? null : minutes.getAsString());
            LongSessionPrompt.finish(event, args[1], sessionStart, OptionalDouble.of(reported));
        } catch (NumberFormatException malformed) {
            Replies.error(event, "Expired form", "End the session again.");
        } catch (InteractionErrors.InputFailure | InteractionErrors.StateFailure refused) {
            // The prompt stays, so the user can try again with a valid time.
            Replies.error(event, "Session not ended", InteractionErrors.userMessage(refused));
        }
    }
}
