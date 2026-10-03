package org.bunnys.modals;

import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import org.bunnys.bunnynexus.timers.Timers;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.modals.BunnyModal;
import org.bunnys.handler.utils.InteractionErrors;
import org.bunnys.utils.ErrorReporter;
import org.bunnys.utils.Replies;
import org.bunnys.utils.SystemEmbeds;

/** The typed confirmation that archives a semester: {@code semester_end_modal:<owner>:<requestedAt>:<revision>}. */
@SuppressWarnings("unused") // Discovered reflectively by ModalRouter.
public class SemesterEndModal extends BunnyModal {
    @Override public boolean deferEditBeforeDispatch() { return true; }

    @Override
    public String getPrefix() {
        return "semester_end_modal";
    }

    @Override
    public void execute(BunnyHub client, ModalInteractionEvent event, String[] args) {
        if (args.length < 4) {
            Replies.error(event, "Expired form", "Run `/timer end_semester` again.");
            return;
        }
        String ownerId = args[1];
        long requestTime;
        Long confirmedRevision;
        try {
            requestTime = Long.parseLong(args[2]);
            confirmedRevision = Timers.parseRevisionToken(args[3]);
        } catch (NumberFormatException e) {
            Replies.error(event, "Expired form", "Run `/timer end_semester` again.");
            return;
        }
        if (!event.getUser().getId().equals(ownerId)) {
            Replies.error(event, "Access denied", "This form belongs to someone else.");
            return;
        }
        try {
            var recap = new Timers(ownerId, event).processEndSemesterModal(event, requestTime, confirmedRevision);
            // The ping lives in the message content; mentions inside embeds never notify.
            event.getHook().editOriginalEmbeds(recap).setContent("<@" + ownerId + ">").setComponents().queue();
        } catch (InteractionErrors.InputFailure | InteractionErrors.StateFailure e) {
            // Wrong phrase, 5-minute timeout, semester changed or missing.
            Replies.error(event, "Semester not archived", InteractionErrors.userMessage(e));
        } catch (RuntimeException e) {
            Replies.privately(event, SystemEmbeds.crashed(ErrorReporter.report("semester end", null, e), false));
        }
    }
}
