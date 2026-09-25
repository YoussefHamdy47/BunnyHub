package org.bunnys.handler.router.modals;

import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import org.bunnys.handler.BunnyHub;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.ErrorReporter;
import org.bunnys.utils.SystemEmbeds;

import java.util.Map;

import java.util.concurrent.RejectedExecutionException;

@SuppressWarnings("unused")
public class ModalRouter {
    // Complete immutable table, replaced only after successful discovery.
    private static volatile Map<String, BunnyModal> MODALS = Map.of();

    private ModalRouter() {}

    public static void loadModals(String packageName) {
        MODALS = org.bunnys.handler.router.ComponentLoader.load(packageName, BunnyModal.class, handler -> handler.getPrefix().toLowerCase(java.util.Locale.ROOT));
        BunnyLog.info("Loaded " + MODALS.size() + " BunnyModal handlers.");
    }

    public static void clear() { MODALS = Map.of(); }
    /** Registered handler count, for the startup summary. */
    public static int count() {
        return MODALS.size();
    }

    public static void handle(BunnyHub client, ModalInteractionEvent event) {

        try {
            String[] args = event.getModalId().split(":", -1);
            String targetModal = args[0].toLowerCase(java.util.Locale.ROOT);
            BunnyModal modal = MODALS.get(targetModal);

            if (modal != null) {
                if (modal.deferEditBeforeDispatch()) {
                    // Only a modal opened from a message component has a message to edit;
                    // one opened from a slash command must be acknowledged with a reply.
                    var acknowledgement = event.getMessage() != null ? event.deferEdit() : event.deferReply(true);
                    acknowledgement.queue(hook -> dispatch(client, event, modal, args),
                            error -> BunnyLog.error("[ModalRouter] Could not acknowledge " + args[0], error));
                }
                else dispatch(client, event, modal, args);
            } else {
                event.replyEmbeds(SystemEmbeds.error("Unknown Form", "That form is no longer available. Reopen it from the command that created it."))
                        .setEphemeral(true).queue();
            }
        } catch (Exception error) {
            ErrorReporter.reportAndReply("modal routing", error, event);
        }
    }

    private static void dispatch(BunnyHub client, ModalInteractionEvent event, BunnyModal modal, String[] args) {
        try {
            client.executeForUser(event.getUser().getId(), () -> {
                    try {
                        modal.execute(client, event, args);
                    } catch (Throwable err) {
                        ErrorReporter.reportAndReply("modal " + args[0], err, event);
                    }
                });
        } catch (RejectedExecutionException rex) {
            BunnyLog.warning("[ModalRouter] Modal interaction rejected under load: " + event.getModalId());
            if (!event.isAcknowledged()) {
                event.replyEmbeds(SystemEmbeds.busy())
                        .setEphemeral(true).queue(null, e -> {});
            }
            else event.getHook().sendMessageEmbeds(SystemEmbeds.busy()).setEphemeral(true).queue();
        } catch (Throwable e) {
            // Routing itself failed, before any handler ran. Same reporting path as a
            // handler crash: this is exactly as unhandled, and just as invisible without it.
            ErrorReporter.reportAndReply("modal routing " + event.getModalId(), e, event);
        }
    }
}
