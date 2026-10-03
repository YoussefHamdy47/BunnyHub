package org.bunnys.bunnynexus.commands.timer;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.bunnys.bunnynexus.timers.SessionEmbeds;
import org.bunnys.bunnynexus.timers.buttons.LongSessionPrompt;
import org.bunnys.bunnynexus.timers.buttons.SessionMenuManager;
import org.bunnys.bunnynexus.timers.services.PendingSessionManager;
import org.bunnys.bunnynexus.timers.services.TimerSessionService;
import org.bunnys.handler.utils.InteractionErrors;

/** Recovery path for sessions whose menu message was deleted or became unreachable. */
public final class EndSession extends TimerSubcommand {
    public EndSession() {
        setName("end-session");
        setDescription("End your current study session, even if its menu is gone.");
    }

    @Override
    void run(SlashCommandInteractionEvent event, String userId) {
        var session = TimerSessionService.getTimerDataOrThrow(userId).getSessionData();
        if (session.getSessionStartTime() == null) {
            if (!PendingSessionManager.cancelPendingSession(userId))
                throw new InteractionErrors.StateFailure("You don't have an active session.");
            event.getHook().editOriginalEmbeds(SessionEmbeds.cancelled()).queue();
            return;
        }
        // Forgotten timers are the usual reason for this command: confirm long sessions before ending them.
        var longSession = TimerSessionService.longSession(userId);
        if (longSession.isPresent()) {
            event.getHook().editOriginalEmbeds(LongSessionPrompt.embed(longSession.get()))
                    .setComponents(LongSessionPrompt.controls(userId, longSession.get())).queue();
            return;
        }
        String channelId = session.getChannelID();
        String messageId = session.getMessageID();
        String recap = TimerSessionService.stopSession(userId, event, true);
        SessionMenuManager.closeMenu(event.getJDA(), userId, channelId, messageId);
        event.getHook().editOriginalEmbeds(SessionEmbeds.concluded(recap, event.getUser())).queue();
    }
}
