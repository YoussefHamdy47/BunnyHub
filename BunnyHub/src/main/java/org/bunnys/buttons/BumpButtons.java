package org.bunnys.buttons;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.bunnynexus.bump.BumpReminders;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.buttons.BunnyButton;
import org.bunnys.handler.utils.InteractionErrors;
import org.bunnys.utils.Replies;

/** <b>I just bumped</b> on the bump reminder DM: the two hours start again from the click. */
@SuppressWarnings("unused") // Discovered reflectively by ButtonRouter.
public class BumpButtons extends BunnyButton {
    @Override public String getPrefix() { return "bump"; }

    @Override public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        try {
            if (!client.getCommandRegistry().getDeveloperIds().contains(event.getUser().getId())) {
                Replies.error(event, "Access denied", "This reminder belongs to the bot owner.");
                return;
            }
            var reminders = BumpReminders.current().orElse(null);
            if (reminders == null) {
                Replies.error(event, "Reminders off", "Bump reminders are not running right now.");
                return;
            }
            event.editMessage(reminders.bumpedNow().edit()).queue();
        } catch (RuntimeException e) {
            InteractionErrors.report(event, e);
        }
    }
}
