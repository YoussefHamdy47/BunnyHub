package org.bunnys.selects;

import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import org.bunnys.bunnynexus.freebies.FreebieMessages;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.selects.BunnySelect;
import java.util.List;

/** Launcher picker on a pending review message. Same owner + review-channel guard as the review buttons. */
@SuppressWarnings("unused") // Discovered reflectively by SelectRouter.
public class FreebieStoreSelect extends BunnySelect {
    @Override public String getPrefix() { return FreebieMessages.STORE_MENU_PREFIX; }
    @Override public long cooldownMillis() { return WRITE_COOLDOWN; }

    @Override
    public void execute(BunnyHub client, StringSelectInteractionEvent event, String[] args) {
        var system = FreebieSystem.current().orElse(null);
        String refusal = null;
        if (system == null) refusal = "Free-game alerts are not running.";
        else if (!system.config().isOwner(event.getUser().getId()) || !system.config().isReviewChannel(event.getChannelId()))
            refusal = "Only the bot owner can review free-game alerts.";
        else if (args.length != 2 || event.getValues().size() != 1) refusal = "This control is malformed.";
        if (refusal != null) {
            event.reply(refusal).setEphemeral(true).setAllowedMentions(List.of()).queue();
            return;
        }
        // The change edits the review message and reads the audience; acknowledge before that work.
        event.deferReply(true).complete();
        String result = system.changeStore(event.getUser().getId(), args[1], event.getValues().getFirst());
        event.getHook().sendMessage(result).setEphemeral(true).setAllowedMentions(List.of()).queue();
    }
}
