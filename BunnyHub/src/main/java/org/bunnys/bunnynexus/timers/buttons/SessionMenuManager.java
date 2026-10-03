package org.bunnys.bunnynexus.timers.buttons;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.bunnynexus.timers.SessionEmbeds;
import org.bunnys.bunnynexus.timers.Timers;
import org.bunnys.bunnynexus.timers.services.PendingSessionManager;
import org.bunnys.bunnynexus.timers.services.SessionClock;
import org.bunnys.bunnynexus.timers.services.TimerSessionService;
import org.bunnys.handler.utils.InteractionErrors;
import org.bunnys.utils.AppDesign;
import org.bunnys.utils.ErrorReporter;
import org.bunnys.utils.Replies;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The study-session menu: {@code session:<action>:<owner>}. Runs on a command worker after the router acknowledged
 * the click with a deferred edit, so every answer goes through the interaction hook.
 */
public final class SessionMenuManager {
    private static final String PREFIX = "session";
    /** The main card is re-rendered at most this often per user; telemetry is always answered privately. */
    private static final long REFRESH_INTERVAL_MS = 15_000;
    private static final Map<String, Long> lastRefresh = new ConcurrentHashMap<>();

    public enum SessionState { PENDING, RUNNING, PAUSED, ENDED }

    public enum SessionAction {
        START("start", "▶ Start"),
        PAUSE("pause", "⏸ Pause"),
        RESUME("resume", "▶ Resume"),
        STATS("stats", "💎 Telemetry"),
        END("end", "End Session");

        public final String id;
        public final String label;

        SessionAction(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public static SessionAction fromString(String id) {
            for (SessionAction action : values())
                if (action.id.equalsIgnoreCase(id)) return action;
            return null;
        }
    }

    private SessionMenuManager() {}

    public static List<Button> buildButtons(String userId, SessionState state) {
        boolean pending = state == SessionState.PENDING, running = state == SessionState.RUNNING, paused = state == SessionState.PAUSED;
        Button first = button(SessionAction.START, userId, false).withDisabled(!pending);
        Button second = paused ? button(SessionAction.RESUME, userId, false)
                : button(SessionAction.PAUSE, userId, false).withDisabled(!running);
        Button stats = button(SessionAction.STATS, userId, true).withDisabled(!running);
        Button end = Button.danger(PREFIX + ":" + SessionAction.END.id + ":" + userId, pending ? "Cancel" : SessionAction.END.label)
                .withEmoji(Emoji.fromFormatted(AppDesign.Emojis.STOP)).withDisabled(!(pending || running));
        return List.of(first, second, stats, end);
    }

    private static Button button(SessionAction action, String userId, boolean primary) {
        String id = PREFIX + ":" + action.id + ":" + userId;
        return primary ? Button.primary(id, action.label) : Button.secondary(id, action.label);
    }

    public static void handle(ButtonInteractionEvent event, String actionId, String targetUserId) {
        if (!event.getUser().getId().equals(targetUserId)) {
            Replies.error(event, "Access denied", "This study session belongs to someone else.");
            return;
        }
        SessionAction action = SessionAction.fromString(actionId);
        if (action == null) return;

        try {
            if (!PendingSessionManager.matches(targetUserId, event.getMessageId())) {
                var active = TimerSessionService.getTimerDataOrThrow(targetUserId).getSessionData();
                if (!event.getMessageId().equals(active.getMessageID()))
                    throw new InteractionErrors.StateFailure("This session menu has expired. Use your current session menu.");
            }
            switch (action) {
                case START -> start(event, targetUserId);
                case PAUSE -> pause(event, targetUserId);
                case RESUME -> resume(event, targetUserId);
                case STATS -> stats(event, targetUserId);
                case END -> end(event, targetUserId);
            }
        } catch (RuntimeException e) {
            // Authored failures are expected; anything else is a bug and must reach the logs.
            if (!(e instanceof InteractionErrors.InputFailure || e instanceof InteractionErrors.StateFailure))
                ErrorReporter.report("session menu " + action.id, event.isFromGuild() ? event.getGuild().getId() : null, e);
            Replies.error(event, "Action failed", InteractionErrors.userMessage(e));
            // A rejected transition usually means the buttons drifted from the stored state
            // (e.g. an earlier edit failed). Redraw them so the user is never locked out.
            if (e instanceof InteractionErrors.StateFailure) resync(event, targetUserId);
        }
    }

    private static void start(ButtonInteractionEvent event, String userId) {
        var pending = PendingSessionManager.getAndRemove(userId, event.getMessageId());
        if (pending == null) {
            Replies.error(event, "Session expired", "This session was never started and has expired. Run `/timer start` again.");
            return;
        }
        MessageEmbed card;
        try {
            card = new Timers(userId, event).startSession(event.getMessageId(), pending.channelId, pending.guildId,
                    pending.topic, pending.objective);
        } catch (RuntimeException failure) {
            // The pending entry is already consumed; close this menu instead of leaving a dead Start button.
            event.getHook().editOriginalComponents(ActionRow.of(buildButtons(userId, SessionState.ENDED))).queue(null, ignored -> {});
            throw failure;
        }
        event.getHook().editOriginalEmbeds(card).setComponents(ActionRow.of(buildButtons(userId, SessionState.RUNNING))).queue();
    }

    private static void pause(ButtonInteractionEvent event, String userId) {
        TimerSessionService.pauseSession(userId);
        var timers = new Timers(userId, event);
        MessageEmbed card = timers.refreshMainEmbed(event.getMessage().getEmbeds().getFirst());
        long now = System.currentTimeMillis();
        var note = SessionEmbeds.paused(SessionClock.of(timers.session(), now).activeStudy(), now);
        event.getHook().editOriginalEmbeds(card).setComponents(ActionRow.of(buildButtons(userId, SessionState.PAUSED)))
                .queue(done -> sendBriefly(event, note));
    }

    private static void resume(ButtonInteractionEvent event, String userId) {
        long breakMs = TimerSessionService.unpauseSession(userId);
        MessageEmbed card = new Timers(userId, event).refreshMainEmbed(event.getMessage().getEmbeds().getFirst());
        event.getHook().editOriginalEmbeds(card).setComponents(ActionRow.of(buildButtons(userId, SessionState.RUNNING)))
                .queue(done -> sendBriefly(event, SessionEmbeds.resumed(breakMs)));
    }

    private static void stats(ButtonInteractionEvent event, String userId) {
        long now = System.currentTimeMillis();
        lastRefresh.entrySet().removeIf(entry -> now - entry.getValue() > REFRESH_INTERVAL_MS);
        var timers = new Timers(userId, event);
        MessageEmbed telemetry = SessionEmbeds.telemetry(timers.session(), now);
        if (lastRefresh.putIfAbsent(userId, now) != null) {
            event.getHook().sendMessageEmbeds(telemetry).setEphemeral(true).queue();
            return;
        }
        MessageEmbed card = timers.refreshMainEmbed(event.getMessage().getEmbeds().getFirst());
        event.getHook().editOriginalEmbeds(card)
                .queue(done -> event.getHook().sendMessageEmbeds(telemetry).setEphemeral(true).queue());
    }

    private static void end(ButtonInteractionEvent event, String userId) {
        if (PendingSessionManager.getAndRemove(userId, event.getMessageId()) != null) {
            event.getHook().editOriginalEmbeds(SessionEmbeds.cancelled())
                    .setComponents(ActionRow.of(buildButtons(userId, SessionState.ENDED))).queue();
            return;
        }
        // 10+ hours: ask first. A forgotten timer and an all-nighter look the same, so the user decides.
        var longSession = TimerSessionService.longSession(userId);
        if (longSession.isPresent()) {
            event.getHook().sendMessageEmbeds(LongSessionPrompt.embed(longSession.get()))
                    .setComponents(LongSessionPrompt.controls(userId, longSession.get())).setEphemeral(true).queue();
            return;
        }
        String recap = TimerSessionService.stopSession(userId, event);
        lastRefresh.remove(userId);
        event.getHook().editOriginalComponents(ActionRow.of(buildButtons(userId, SessionState.ENDED))).queue();
        // The ping lives in the message content; mentions inside embeds never notify.
        event.getHook().sendMessage("<@" + userId + ">").addEmbeds(SessionEmbeds.concluded(recap, event.getUser())).queue();
    }

    /** Best effort: disables the buttons of a session menu that may be deleted or in a channel the bot can no longer see. */
    public static void closeMenu(JDA jda, String userId, String channelId, String messageId) {
        lastRefresh.remove(userId);
        if (channelId == null || messageId == null) return;
        MessageChannel channel = jda.getChannelById(MessageChannel.class, channelId);
        if (channel == null) return;
        try {
            channel.editMessageComponentsById(messageId, ActionRow.of(buildButtons(userId, SessionState.ENDED))).queue(null, ignored -> {});
        } catch (RuntimeException ignored) {
            // Missing permissions are reported synchronously by JDA; the session is already ended.
        }
    }

    /** Pause/resume notes are public but only relevant for a minute. */
    private static void sendBriefly(ButtonInteractionEvent event, MessageEmbed note) {
        event.getHook().sendMessageEmbeds(note).queue(message -> message.delete().queueAfter(1, TimeUnit.MINUTES, null, ignored -> {}));
    }

    /** Runs on the command executor; performs a database read, so never call it from a JDA callback. */
    private static void resync(ButtonInteractionEvent event, String userId) {
        try {
            if (PendingSessionManager.matches(userId, event.getMessageId())) return;
            var session = TimerSessionService.getTimerDataOrThrow(userId).getSessionData();
            if (!event.getMessageId().equals(session.getMessageID())) return;
            SessionState state = session.getSessionStartTime() == null ? SessionState.ENDED
                    : session.getSessionBreaks().getSessionBreakStart() != null ? SessionState.PAUSED
                    : SessionState.RUNNING;
            event.getHook().editOriginalComponents(ActionRow.of(buildButtons(userId, state))).queue(null, ignored -> {});
        } catch (RuntimeException ignored) {
            // Best effort only; the error reply was already sent.
        }
    }
}
