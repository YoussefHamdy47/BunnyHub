package org.bunnys.bunnynexus.commands.timer;

import org.bunnys.handler.utils.InteractionErrors;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.bunnynexus.timers.buttons.SessionMenuManager;
import org.bunnys.bunnynexus.timers.services.PendingSessionManager;
import org.bunnys.bunnynexus.timers.services.TimerSessionService;
import org.bunnys.utils.AppDesign;

import java.time.Instant;

/** Recovery path for sessions whose menu message was deleted or became unreachable. */
public final class EndSession extends BunnySubcommand {
    public EndSession() {
        setName("end-session");
        setDescription("End your current study session, even if its menu is gone.");
    }

    @Override
    public void execute(BunnyHub client, org.bunnys.handler.commands.context.CommandContext context) {
        var event = ((org.bunnys.handler.commands.context.SlashContext) context).event();
        String userId = event.getUser().getId();

        try {
            var session = TimerSessionService.getTimerDataOrThrow(userId).getSessionData();
            if (session.getSessionStartTime() == null) {
                String message = PendingSessionManager.cancelPendingSession(userId)
                        ? "Your pending session was cancelled."
                        : "You don't have an active session.";
                event.getHook().editOriginal("> " + message).queue();
                return;
            }

            String channelId = session.getChannelID();
            String messageId = session.getMessageID();
            String recap = TimerSessionService.stopSession(userId, event, true);
            disableOldMenu(event.getJDA(), userId, channelId, messageId);

            EmbedBuilder eb = new EmbedBuilder()
                    .setColor(AppDesign.ColorCodes.CYAN)
                    .setTitle("💎 Session Concluded")
                    .setDescription("**Session Recap**\n\n" + recap)
                    .setFooter("🌴 Timer offline", event.getUser().getEffectiveAvatarUrl())
                    .setTimestamp(Instant.now());
            event.getHook().editOriginalEmbeds(eb.build()).queue();

        } catch (IllegalStateException | IllegalArgumentException e) {
            event.getHook().editOriginal("> " + AppDesign.Emojis.ERROR + " **Action failed:** " + InteractionErrors.userMessage(e))
                    .queue();
        }
    }

    /** Best effort: the menu may be deleted or in a channel the bot can no longer see. */
    private static void disableOldMenu(net.dv8tion.jda.api.JDA jda, String userId, String channelId, String messageId) {
        if (channelId == null || messageId == null) return;
        MessageChannel channel = jda.getChannelById(MessageChannel.class, channelId);
        if (channel == null) return;
        try {
            channel.editMessageComponentsById(messageId, net.dv8tion.jda.api.components.actionrow.ActionRow.of(
                            SessionMenuManager.buildButtons(userId, SessionMenuManager.SessionState.ENDED)))
                    .queue(null, ignored -> {});
        } catch (RuntimeException ignored) {
            // Missing permissions are reported synchronously by JDA; the session is already ended.
        }
    }
}
