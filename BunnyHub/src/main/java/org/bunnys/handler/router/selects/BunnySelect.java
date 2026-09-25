package org.bunnys.handler.router.selects;

import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import org.bunnys.handler.BunnyHub;

import java.util.concurrent.TimeUnit;

/**
 * A string select-menu handler, resolved by the prefix of the component id.
 *
 * <p>The exact shape of {@link org.bunnys.handler.router.buttons.BunnyButton}, for the
 * same reasons: the prefix routes, and rate limiting is a property of the handler rather
 * than of the router. The default is {@link #NO_COOLDOWN}, which suits every menu whose
 * job is to re-render something the user is already looking at.
 *
 * <p>Entity select menus (roles, users, channels) are not routed here. Those are only
 * ever used inside modals, where the submission carries their values and
 * {@code ModalRouter} already handles it.
 */
public abstract class BunnySelect {

    /** Free-choosing: navigation, filtering, anything read-only. */
    public static final long NO_COOLDOWN = 0L;

    /** Enough to swallow a double-selection on anything that writes. */
    public static final long WRITE_COOLDOWN = TimeUnit.SECONDS.toMillis(3);

    /**
     * The prefix that routes to this handler (e.g. {@code "helppick"} for
     * {@code "helppick:2"}).
     */
    public abstract String getPrefix();

    /**
     * How long a user must wait between uses of menus carrying this prefix.
     *
     * <p>Return {@link #NO_COOLDOWN} - the default - for anything a user is expected to
     * work through option by option. Return a real duration only where a repeat costs
     * something: a write, an external API call, or a state change.
     */
    public long cooldownMillis() {
        return NO_COOLDOWN;
    }

    /**
     * Executes the menu logic.
     *
     * @param args the component id split on {@code ':'}, prefix included. The chosen
     *             values are on the event, via {@code getValues()}.
     */
    public abstract void execute(BunnyHub client, StringSelectInteractionEvent event, String[] args);
}
