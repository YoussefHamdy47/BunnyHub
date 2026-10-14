package org.bunnys.buttons;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.entities.User;
import org.bunnys.bunnynexus.freebies.FreebieDm;
import org.bunnys.bunnynexus.freebies.FreebieDmService;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.buttons.BunnyButton;
import java.util.List;

/**
 * "Unsubscribe" on every game DM and the consent DM. The id carries no user: it removes the clicker's own
 * subscription only, so it keeps working after restarts and cannot act for someone else.
 */
@SuppressWarnings("unused") // Discovered reflectively by ButtonRouter.
public class FreebieDmButtons extends BunnyButton {
    @Override public String getPrefix() { return FreebieDm.UNSUBSCRIBE_PREFIX; }
    @Override public long cooldownMillis() { return ECONOMY_COOLDOWN; }

    @Override
    public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        var system = FreebieSystem.current().orElse(null);
        if (system == null) { reply(event, "Free-game alerts are not available right now."); return; }
        event.deferReply(true).complete();
        event.getHook().sendMessage(unsubscribe(system.dms(), event.getUser())).setEphemeral(true)
                .setAllowedMentions(List.of()).queue();
    }

    /** Whatever the id says, only the clicker's own subscription is touched. */
    static String unsubscribe(FreebieDmService dms, User clicker) {
        return dms.unsubscribe(clicker.getId()) ? "Done. You won't get free-game DMs any more. `/free-games dms` turns them back on."
                : "You aren't subscribed to free-game DMs.";
    }

    private static void reply(ButtonInteractionEvent event, String text) {
        event.reply(text).setEphemeral(true).setAllowedMentions(List.of()).queue();
    }
}
