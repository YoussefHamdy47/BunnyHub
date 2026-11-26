package org.bunnys.buttons;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.cards.Card;
import org.bunnys.handler.router.buttons.BunnyButton;
import org.bunnys.utils.AppDesign;

/**
 * "Add as free game" / "Not a giveaway" on a console tip in a review channel ({@code console_tip:<action>:<tip>}).
 * Owners only, inside a review channel.
 */
@SuppressWarnings("unused") // Discovered reflectively by ButtonRouter.
public class ConsoleTipButtons extends BunnyButton {
    @Override public String getPrefix() { return FreebieSystem.CONSOLE_TIP_BUTTON; }
    @Override public long cooldownMillis() { return ECONOMY_COOLDOWN; }

    @Override
    public void execute(BunnyHub client, ButtonInteractionEvent event, String[] ignored) {
        var system = FreebieSystem.current().orElse(null);
        if (system == null) { deny(event, FreebieSystem.unavailable()); return; }
        if (!system.config().isOwner(event.getUser().getId()) || !system.config().isReviewChannel(event.getChannelId())) {
            deny(event, "Only the bot owner can review free-game alerts.");
            return;
        }
        String[] args = event.getComponentId().split(":", 3);
        if (args.length != 3 || !args[2].matches("[0-9a-f]{16}")) { deny(event, "This control is malformed."); return; }
        switch (args[1]) {
            case "add" -> event.replyModal(FreebieSystem.consoleTipForm(args[2])).queue();
            case "dismiss" -> {
                event.deferEdit().queue();
                String result = system.dismissConsoleTip(event.getUser().getId(), args[2]);
                event.getHook().editOriginal(Card.builder().text("**Console tip** · " + result + " (<@" + event.getUser().getId() + ">)")
                        .build().edit()).queue();
            }
            default -> deny(event, "This control is malformed.");
        }
    }

    private static void deny(ButtonInteractionEvent event, String message) {
        event.reply(Card.builder().text("> " + AppDesign.Emojis.ERROR + " " + message).build().message()).setEphemeral(true).queue();
    }
}
