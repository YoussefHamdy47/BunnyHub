package org.bunnys.buttons;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.bunnys.bunnynexus.freebies.FreebieMessages;
import org.bunnys.bunnynexus.freebies.FreebieReviewCleanup;
import org.bunnys.bunnynexus.freebies.FreebieSourceDecisions;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.cards.Card;
import org.bunnys.handler.router.buttons.BunnyButton;
import org.bunnys.utils.AppDesign;
import java.util.List;

/**
 * Approve / confirm / reject / stop / clear finished controls on freebie review messages.
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
        args = event.getComponentId().split(":", 3);
        if (args.length != 3) { deny(event, "This control is malformed."); return; }
        String action = args[1], offerId = args[2], actor = event.getUser().getId();
        if (action.startsWith("broadcast-") || action.startsWith("broadcastq-")) {
            event.deferEdit().complete();
            String result = system.confirmAnnouncement(actor, offerId, action);
            replace(event, result, result.startsWith("Sending") ? FreebieSystem.announcementStopControls(offerId) : List.of());
            return;
        }
        if (action.equals("upcoming")) {
            // The weekly "Free next week" card: the announcement preview, privately, with its send buttons.
            event.deferReply(true).complete();
            switch (system.upcomingAnnouncement(actor)) {
                case org.bunnys.bunnynexus.freebies.FreebieBroadcasts.Preview preview ->
                        event.getHook().sendMessage(preview.message()).setEphemeral(true).queue();
                case org.bunnys.bunnynexus.freebies.FreebieBroadcasts.Problem problem -> followUp(event, problem.text());
            }
            return;
        }
        if (action.equals("broadcaststop")) {
            event.deferEdit().complete();
            replace(event, system.stopAnnouncement(actor, offerId), List.of());
            return;
        }
        if (action.startsWith("confirmrelease-")) {
            event.deferEdit().complete();
            replace(event, system.confirmRelease(actor, offerId, action), List.of());
            return;
        }
        if (action.startsWith("source-")) {
            // The question message itself shows the decision and loses its decision buttons, so it no longer looks
            // open and Clear finished removes it. Answered elsewhere: the same, marked as already answered.
            event.deferEdit().complete();
            var outcome = system.resolveSource(actor, offerId, action);
            if (outcome.status() == FreebieSourceDecisions.Status.INVALID) { followUp(event, "> " + AppDesign.Emojis.ERROR + " " + outcome.message()); return; }
            var message = event.getMessage();
            event.getHook().editOriginal(FreebieSourceDecisions.decided(message.getContentRaw(), outcome, actor, system.now()))
                    .setComponents(message.isEphemeral() ? List.of() : FreebieMessages.withClear(List.of()))
                    .setAllowedMentions(List.of()).queue();
            return;
        }
        // Runs on a command worker: acknowledge first so database work can never outlast Discord's 3 s window.
        switch (action) {
            case FreebieMessages.CLEAR_ACTION -> {
                event.deferReply(true).complete();
                if (!(event.getChannel() instanceof GuildMessageChannel channel)) { followUp(event, "This only works in the review channel."); return; }
                FreebieReviewCleanup.clear(channel, system::sourceQuestionOpen).whenComplete((result, failure) -> followUp(event, failure != null
                        ? "> " + AppDesign.Emojis.ERROR + " Couldn't read the channel history. Check that I have Read Message History here."
                        : "Cleared **" + result.deleted() + "** finished message" + (result.deleted() == 1 ? "" : "s")
                        + (result.kept() > 0 ? ", kept **" + result.kept() + "** that still need you." : ".")));
            }
            case "confirm", FreebieMessages.CONFIRM_QUIET -> {
                event.deferEdit().complete();
                replace(event, system.confirmApproval(actor, offerId, action.equals(FreebieMessages.CONFIRM_QUIET)), List.of());
            }
            case "approve" -> {
                event.deferReply(true).complete();
                switch (system.requestApproval(actor, offerId)) {
                    case FreebieSystem.Prompt prompt -> event.getHook().sendMessage(prompt.message()).setEphemeral(true).queue();
                    case FreebieSystem.Text text -> followUp(event, "> " + AppDesign.Emojis.ERROR + " " + text.message());
                }
            }
            case "approvegroup" -> {
                event.deferReply(true).complete();
                switch (system.requestGroupApproval(actor, offerId)) {
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

    /**
     * Replaces the clicked prompt with {@code text} (and {@code rows}) in the format it was sent in: prompts are cards
     * now, and one sent before the switch is still edited as plain text.
     */
    private static void replace(ButtonInteractionEvent event, String text, List<ActionRow> rows) {
        if (event.getMessage().isUsingComponentsV2())
            event.getHook().editOriginal(Card.builder().text(text).rows(rows).build().edit()).queue();
        else event.getHook().editOriginal(text).setEmbeds().setComponents(rows).setAllowedMentions(List.of()).queue();
    }

    private static void followUp(ButtonInteractionEvent event, String message) {
        event.getHook().sendMessage(Card.builder().text(message).build().message()).setEphemeral(true).queue();
    }

    private static void deny(ButtonInteractionEvent event, String message) {
        event.reply(Card.builder().text("> " + AppDesign.Emojis.ERROR + " " + message).build().message()).setEphemeral(true).queue();
    }
}

