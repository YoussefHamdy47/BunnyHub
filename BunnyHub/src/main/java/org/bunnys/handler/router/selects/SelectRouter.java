package org.bunnys.handler.router.selects;

import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.ComponentCooldowns;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.ErrorReporter;
import org.bunnys.utils.SystemEmbeds;

import java.util.Map;

import java.util.concurrent.RejectedExecutionException;

/**
 * Routes string select-menu choices to their handler.
 *
 * <p>Deliberately the same shape as {@code ButtonRouter}, down to the backpressure and
 * the crash reply, because a select menu is the same kind of thing as a button: a
 * component id, a prefix, a bounded pool, and a handler that may fail.
 */
public final class SelectRouter {

    private static volatile Map<String, BunnySelect> HANDLERS = Map.of();

    private SelectRouter() {}

    public static void loadSelects(String packageName) {
        HANDLERS = org.bunnys.handler.router.ComponentLoader.load(packageName, BunnySelect.class, handler -> handler.getPrefix());
        BunnyLog.info("Loaded " + HANDLERS.size() + " BunnySelect handlers.");
    }

    public static void clear() { HANDLERS = Map.of(); }
    /** Registered handler count, for the startup summary. */
    public static int count() {
        return HANDLERS.size();
    }

    public static void handle(BunnyHub client, StringSelectInteractionEvent event) {

        String componentId = event.getComponentId();
        if (componentId.isBlank())
            return;

        String[] parts = componentId.split(":", -1);
        BunnySelect handler = HANDLERS.get(parts[0]);

        if (handler == null) {
            event.replyEmbeds(SystemEmbeds.error("Expired Control", "Reopen this menu from its command."))
                    .setEphemeral(true).queue(null, error -> {});
            return;
        }

        var reservation = ComponentCooldowns.reserve("select:" + parts[0], event.getUser().getId(), handler.cooldownMillis());
        long remaining = reservation.remainingMillis();
        if (remaining > 0) {
            event.replyEmbeds(SystemEmbeds.buttonCooldown(remaining)).setEphemeral(true).queue(null, e -> {});
            return;
        }

        try {
            // Offload to the bounded pool so gateway threads never run command logic.
            client.executeForUser(event.getUser().getId(), () -> {
                try {
                    handler.execute(client, event, parts);
                } catch (Throwable err) {
                    ErrorReporter.reportAndReply("select " + componentId, err, event);
                }
            });
        } catch (RejectedExecutionException rex) {
            BunnyLog.warning("[SelectRouter] " + componentId + " rejected under load");
            // The choice never ran, so it must not hold a cooldown against the user.
            reservation.release();
            if (!event.isAcknowledged())
                event.replyEmbeds(SystemEmbeds.busy()).setEphemeral(true).queue(null, e -> {});
        }
    }
}
