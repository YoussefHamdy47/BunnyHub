package org.bunnys.buttons;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.bunnynexus.freebies.FreebieMessages;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.buttons.BunnyButton;
import org.bunnys.utils.AppDesign;

/**
 * Approve / confirm / reject / stop controls on freebie review messages.
 * Only owners listed in FREEBIE_OWNER_IDS, clicking inside FREEBIE_REVIEW_CHANNEL_ID, can use them.
 */
@SuppressWarnings("unused")
public class FreebieReviewButtons extends BunnyButton {
    @Override public String getPrefix() { return FreebieMessages.BUTTON_PREFIX; }
    @Override public long cooldownMillis() { return ECONOMY_COOLDOWN; }

    @Override
    public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        var system = FreebieSystem.current().orElse(null);
        if (system == null) { deny(event, "Free-game alerts are not running."); return; }
        var config = system.config();
        if (!config.isOwner(event.getUser().getId()) || !event.getChannelId().equals(config.reviewChannelId())) {
            deny(event, "Only the bot owner can review free-game alerts.");
            return;
        }
        if (args.length != 3) { deny(event, "This control is malformed."); return; }
        String action = args[1], offerId = args[2], actor = event.getUser().getId();
        switch (action) {
            case "approve" -> {
                switch (system.requestApproval(actor, offerId)) {
                    case FreebieSystem.Prompt prompt -> event.reply(prompt.message()).setEphemeral(true).queue();
                    case FreebieSystem.Text text -> deny(event, text.message());
                }
            }
            case "confirm" -> {
                // Runs on the command worker, so blocking until Discord acknowledges is allowed here.
                event.deferEdit().complete();
                String result = system.confirmApproval(actor, offerId);
                event.getHook().editOriginal(result).setComponents().queue();
            }
            case "reject" -> reply(event, system.reject(actor, offerId));
            case "stop" -> reply(event, system.stop(actor, offerId));
            default -> deny(event, "Unknown action.");
        }
    }

    private static void reply(ButtonInteractionEvent event, String message) {
        event.reply(message).setEphemeral(true).setAllowedMentions(java.util.List.of()).queue();
    }
    private static void deny(ButtonInteractionEvent event, String message) {
        event.reply("> " + AppDesign.Emojis.ERROR + " " + message).setEphemeral(true).setAllowedMentions(java.util.List.of()).queue();
    }
}
