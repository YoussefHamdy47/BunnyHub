package org.bunnys.selects;

import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import org.bunnys.bunnynexus.freebies.FreebieMessages;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.selects.BunnySelect;
import java.util.List;

/** Launcher picker on a pending review message. Same owner + review-channel guard as the review buttons. */
@SuppressWarnings("unused")
public class FreebieStoreSelect extends BunnySelect {
    @Override public String getPrefix() { return FreebieMessages.STORE_MENU_PREFIX; }
    @Override public long cooldownMillis() { return WRITE_COOLDOWN; }

    @Override
    public void execute(BunnyHub client, StringSelectInteractionEvent event, String[] args) {
        var system = FreebieSystem.current().orElse(null);
        String reply;
        if (system == null) reply = "Free-game alerts are not running.";
        else if (!system.config().isOwner(event.getUser().getId()) || !event.getChannelId().equals(system.config().reviewChannelId()))
            reply = "Only the bot owner can review free-game alerts.";
        else if (args.length != 2 || event.getValues().size() != 1) reply = "This control is malformed.";
        else reply = system.changeStore(event.getUser().getId(), args[1], event.getValues().getFirst());
        event.reply(reply).setEphemeral(true).setAllowedMentions(List.of()).queue();
    }
}
