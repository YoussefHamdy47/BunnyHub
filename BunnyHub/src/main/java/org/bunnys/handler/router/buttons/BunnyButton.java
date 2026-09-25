package org.bunnys.handler.router.buttons;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.handler.BunnyHub;

import java.util.concurrent.TimeUnit;

/**
 * A button handler, resolved by the prefix of the component id.
 *
 * <h2>Self-declared cooldowns</h2>
 * Rate limiting is a property of the button, not of the router. Each handler states its
 * own {@link #cooldownMillis()}; {@link ButtonRouter} enforces whatever it is told and
 * knows nothing about which buttons exist.
 *
 * <p>This replaces a single hardcoded 2-second cooldown applied indiscriminately to
 * every button in the bot - which throttled manga page-turning, where rapid clicking is
 * the entire point, while being too short to actually protect a destructive economy
 * action. The default here is {@link #NO_COOLDOWN}, so a new button is unthrottled
 * until it says otherwise, and adding one never involves editing a central map.
 */
public abstract class BunnyButton {
    /** Acknowledge an edit before scheduling database work; false for buttons opening modals. */
    public boolean deferEditBeforeDispatch() { return false; }

    /** Free-clicking: pagination, navigation, anything read-only. */
    public static final long NO_COOLDOWN = 0L;

    /** Enough to swallow a double-click and a panicked triple-click after it. */
    public static final long ECONOMY_COOLDOWN = TimeUnit.SECONDS.toMillis(3);

    /**
     * The prefix that routes to this handler (e.g. {@code "leg_offer"} for
     * {@code "leg_offer:confirm:123:456"}).
     */
    public abstract String getPrefix();

    /**
     * How long a user must wait between clicks on buttons carrying this prefix.
     *
     * <p>Return {@link #NO_COOLDOWN} - the default - for anything a user is expected to
     * click repeatedly. Return a real duration only where a repeat click costs something:
     * a write, an external API call, or a state change that must not happen twice.
     */
    public long cooldownMillis() {
        return NO_COOLDOWN;
    }

    /**
     * Executes the button logic.
     *
     * @param args the component id split on {@code ':'}, prefix included
     */
    public abstract void execute(BunnyHub client, ButtonInteractionEvent event, String[] args);
}
