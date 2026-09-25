package org.bunnys.modals;

import org.bunnys.handler.utils.InteractionErrors;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.modals.BunnyModal;
import org.bunnys.bunnynexus.timers.Timers;
import org.bunnys.utils.AppDesign;
import org.bunnys.utils.BunnyLog;

public class SemesterEndModal extends BunnyModal {
    @Override public boolean deferEditBeforeDispatch() { return true; }

    @Override
    public String getPrefix() {
        return "semester_end_modal";
    }

    @Override
    public void execute(BunnyHub client, ModalInteractionEvent event, String[] args) {
        // Prevent silent failure: Acknowledge the event if arguments are malformed
        if (args.length < 4) {
            event.getHook().sendMessage("> " + AppDesign.Emojis.ERROR + " **Expired form:** Run `/timer end_semester` again.").setEphemeral(true).queue();
            return;
        }

        String targetId = args[1];
        long requestTime;
        Long confirmedRevision;

        try {
            requestTime = Long.parseLong(args[2]);
            confirmedRevision = Timers.parseRevisionToken(args[3]);
        } catch (NumberFormatException e) {
            event.getHook().sendMessage("> " + AppDesign.Emojis.ERROR + " **Error:** Invalid request timestamp.").setEphemeral(true).queue();
            return;
        }

        if (!event.getUser().getId().equals(targetId)) {
            event.getHook().sendMessage("> " + AppDesign.Emojis.ERROR + " **Access Denied:** This modal belongs to someone else.").setEphemeral(true).queue();
            return;
        }

        try {
            Timers timerSystem = new Timers(targetId, event);
            MessageEmbed finalRecapEmbed = timerSystem.processEndSemesterModal(event, requestTime, confirmedRevision);

            // By setting the content to the ping, it happens completely outside the embed
            event.getHook().editOriginalEmbeds(finalRecapEmbed)
                    .setContent("<@" + targetId + ">")
                    .setComponents() // This clears the confirmation buttons
                    .queue();

        } catch (IllegalArgumentException | IllegalStateException e) {
            // Catches invalid confirmation phrases, 5-minute timeouts, or missing semesters
            if (!(e instanceof InteractionErrors.InputFailure || e instanceof InteractionErrors.StateFailure))
                BunnyLog.error("[SemesterEndModal] Unexpected failure for user: " + targetId, e);
            event.getHook().sendMessage("> ❌ **Termination aborted:** " + InteractionErrors.userMessage(e))
                    .setEphemeral(true).queue();
        } catch (Exception e) {
            // Log to console AND alert the user so Discord doesn't timeout
            BunnyLog.error("[SemesterEndModal] Critical failure during execution for user: " + targetId, e);
            event.getHook().sendMessage("> " + AppDesign.Emojis.ERROR + " **System Error:** Could not finalize semester archival.")
                    .setEphemeral(true).queue();
        }
    }
}
