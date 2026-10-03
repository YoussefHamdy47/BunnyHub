package org.bunnys.buttons;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.bunnynexus.freebies.FreebieMessages;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.buttons.BunnyButton;
import org.bunnys.utils.AppDesign;
import java.util.List;

/**
 * Approve / confirm / reject / stop controls on freebie review messages.
 * Only owners listed in FREEBIE_OWNER_IDS, clicking inside one of the review channels from .env, can use them.
 */
@SuppressWarnings("unused") // Discovered reflectively by ButtonRouter.
public class FreebieReviewButtons extends BunnyButton {
    @Override public String getPrefix() { return FreebieMessages.BUTTON_PREFIX; }
    @Override public long cooldownMillis() { return ECONOMY_COOLDOWN; }

    @Override
    public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        var system = FreebieSystem.current().orElse(null);
        if (system == null) { deny(event, "Free-game alerts are not running."); return; }
        var config = system.config();
        if (!config.isOwner(event.getUser().getId()) || !config.isReviewChannel(event.getChannelId())) {
            deny(event, "Only the bot owner can review free-game alerts.");
            return;
        }
        if (args.length != 3) { deny(event, "This control is malformed."); return; }
        String action = args[1], offerId = args[2], actor = event.getUser().getId();
        // Runs on a command worker: acknowledge first so database work can never outlast Discord's 3 s window.
        switch (action) {
            case "confirm" -> {
                event.deferEdit().complete();
                String result = system.confirmApproval(actor, offerId);
                event.getHook().editOriginal(result).setComponents().setAllowedMentions(List.of()).queue();
            }
            case "approve" -> {
                event.deferReply(true).complete();
                switch (system.requestApproval(actor, offerId)) {
                    case FreebieSystem.Prompt prompt -> event.getHook().sendMessage(prompt.message()).setEphemeral(true).queue();
                    case FreebieSystem.Text text -> followUp(event, "> " + AppDesign.Emojis.ERROR + " " + text.message());
                }
            }
            case "reject" -> {
                event.deferReply(true).complete();
                followUp(event, system.reject(actor, offerId));
            }
            case "stop" -> {
                event.deferReply(true).complete();
                followUp(event, system.stop(actor, offerId));
            }
            default -> deny(event, "Unknown action.");
        }
    }

    private static void followUp(ButtonInteractionEvent event, String message) {
        event.getHook().sendMessage(message).setEphemeral(true).setAllowedMentions(List.of()).queue();
    }

    private static void deny(ButtonInteractionEvent event, String message) {
        event.reply("> " + AppDesign.Emojis.ERROR + " " + message).setEphemeral(true).setAllowedMentions(List.of()).queue();
    }
}
