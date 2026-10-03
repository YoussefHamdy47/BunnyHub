package org.bunnys.buttons;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.bunnynexus.commands.freebies.FreebieDashboard;
import org.bunnys.bunnynexus.freebies.FreebieSection;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.buttons.BunnyButton;
import java.util.EnumSet;
import java.util.List;

/**
 * Dashboard navigation: open a section's form, open auto-setup, or go back/refresh. Nothing here writes, so there
 * is no cooldown; the clicker must still have Manage Server in the server the dashboard was built for.
 */
@SuppressWarnings("unused") // Discovered reflectively by ButtonRouter.
public class FreebieDashboardButtons extends BunnyButton {
    @Override public String getPrefix() { return FreebieDashboard.NAV_PREFIX; }

    @Override
    public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        var system = FreebieSystem.current().orElse(null);
        if (system == null || args.length < 3 || !FreebieDashboard.canManage(event.getGuild(), event.getMember(), args[2])) {
            deny(event, "You need **Manage Server** in this server to do that.");
            return;
        }
        switch (args[1]) {
            // A form must be the first response, so it is built from the button id alone (no database read).
            case FreebieDashboard.OPEN_SECTION -> FreebieDashboard.sectionForm(args).ifPresentOrElse(
                    form -> event.replyModal(form).queue(), () -> deny(event, "This control is malformed."));
            case FreebieDashboard.AUTO -> event.editMessage(FreebieDashboard.asEdit(
                    FreebieDashboard.autoSetup(args[2], EnumSet.allOf(FreebieSection.class)))).queue();
            case FreebieDashboard.HOME -> {
                event.deferEdit().complete();
                var saved = system.subscriptions().forGuild(args[2]);
                event.getHook().editOriginal(FreebieDashboard.asEdit(FreebieDashboard.dashboard(event.getGuild(), saved, null))).queue();
            }
            default -> deny(event, "Unknown action.");
        }
    }

    private static void deny(ButtonInteractionEvent event, String message) {
        event.reply(message).setEphemeral(true).setAllowedMentions(List.of()).queue();
    }
}
