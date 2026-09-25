package org.bunnys.handler.router.buttons;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.ComponentCooldowns;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.ErrorReporter;
import org.bunnys.utils.SystemEmbeds;

import java.util.Map;

import java.util.concurrent.RejectedExecutionException;

/**
 * Routes button clicks to their handler, and enforces whatever cooldown that handler declares.
 *
 * <h2>Rate limiting</h2>
 * The router holds no cooldown policy of its own. It asks the resolved handler for
 * {@link BunnyButton#cooldownMillis()} and enforces exactly that - zero means the click
 * goes straight through with no cache read at all, which is the case for every
 * pagination control in the bot. The bookkeeping lives in {@link ComponentCooldowns},
 * shared with the select-menu router.
 *
 * <p>This replaces a single hardcoded 2-second cooldown applied indiscriminately to
 * every button in the bot - which throttled manga page-turning, where rapid clicking is
 * the entire point, while being too short to actually protect a destructive economy
 * action.
 */
public final class ButtonRouter {

    private static volatile Map<String, BunnyButton> HANDLERS = Map.of();

    private ButtonRouter() {}

    public static void loadButtons(String packageName) {
        HANDLERS = org.bunnys.handler.router.ComponentLoader.load(packageName, BunnyButton.class, handler -> handler.getPrefix());
        BunnyLog.info("Loaded " + HANDLERS.size() + " BunnyButton handlers.");
    }

    public static void clear() { HANDLERS = Map.of(); }
    /** Registered handler count, for the startup summary. */
    public static int count() {
        return HANDLERS.size();
    }

    public static void handle(BunnyHub client, ButtonInteractionEvent event) {

        String componentId = event.getComponentId();
        if (componentId.isBlank())
            return;

        String[] parts = componentId.split(":", -1);
        BunnyButton handler = HANDLERS.get(parts[0]);

        if (handler == null) {
            event.replyEmbeds(SystemEmbeds.error("Expired Control", "Reopen this menu from its command."))
                    .setEphemeral(true).queue(null, error -> {});
            return;
        }

        var reservation = ComponentCooldowns.reserve("button:" + parts[0], event.getUser().getId(), handler.cooldownMillis());
        long remaining = reservation.remainingMillis();
        if (remaining > 0) {
            event.replyEmbeds(SystemEmbeds.buttonCooldown(remaining)).setEphemeral(true).queue(null, e -> {});
            return;
        }

        if (handler.deferEditBeforeDispatch())
            event.deferEdit().queue(hook -> dispatch(client, event, handler, parts, reservation),
                    error -> reservation.release());
        else dispatch(client, event, handler, parts, reservation);
    }

    private static void dispatch(BunnyHub client, ButtonInteractionEvent event, BunnyButton handler, String[] parts, org.bunnys.handler.CooldownStore.Reservation reservation) {
        String componentId = event.getComponentId();
        try {
            client.executeForUser(event.getUser().getId(), () -> {
                try {
                    handler.execute(client, event, parts);
                } catch (Throwable err) {
                    ErrorReporter.reportAndReply("button " + componentId, err, event);
                }
            });
        } catch (RejectedExecutionException rex) {
            BunnyLog.warning("[ButtonRouter] " + componentId + " rejected under load");
            // The click never ran, so it must not hold a cooldown against the user.
            reservation.release();
            if (!event.isAcknowledged())
                event.replyEmbeds(SystemEmbeds.busy()).setEphemeral(true).queue(null, e -> {});
            else event.getHook().sendMessageEmbeds(SystemEmbeds.busy()).setEphemeral(true).queue();
        }
    }
}
