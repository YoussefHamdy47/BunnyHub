package org.bunnys.bunnynexus.timers.buttons;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.modals.Modal;
import org.bunnys.bunnynexus.timers.SessionEmbeds;
import org.bunnys.bunnynexus.timers.services.TimerSessionService;
import org.bunnys.bunnynexus.timers.services.TimerSessionService.Confirmation;
import org.bunnys.bunnynexus.timers.services.TimerSessionService.LongSession;
import org.bunnys.handler.utils.InteractionErrors.InputFailure;
import org.bunnys.utils.Durations;
import org.bunnys.utils.Embeds;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * "Did you really study that long?" before ending a session of 10 hours or more. The user keeps the tracked time,
 * enters their real study time (validated by the service), or keeps the session running.
 *
 * <p>Ids: {@code session_long:keep|fix|cancel:<owner>:<sessionStart>[:<minimumSecs>:<trackedSecs>]} and the form
 * {@code session_long_time:<owner>:<sessionStart>}. The session start ties every control to one session, so a prompt
 * left open can never end a later session. The minimum and tracked values only label the form.
 */
public final class LongSessionPrompt {
    private LongSessionPrompt() {}

    public static final String BUTTON_PREFIX = "session_long", MODAL_PREFIX = "session_long_time";
    public static final String KEEP = "keep", FIX = "fix", CANCEL = "cancel", HOURS = "hours", MINUTES = "minutes";

    public static MessageEmbed embed(LongSession session) {
        return Embeds.of("⏰", "That's a long session", "Your timer tracked **" + Durations.formatSeconds(session.tracked())
                + "** of study (breaks excluded).\n\nIf you really studied that long, keep it. If the timer ran while you "
                + "were away, enter the time you actually studied instead: it only changes this session's numbers."
                + (session.minimum() > 0 ? "\n\n*" + Durations.formatSeconds(session.minimum())
                + " was already logged when you switched subjects, so that is the minimum.*" : "")).build();
    }

    public static ActionRow controls(String userId, LongSession session) {
        String base = ":" + userId + ":" + session.sessionStart();
        return ActionRow.of(
                Button.success(BUTTON_PREFIX + ":" + KEEP + base, "Yes, I studied " + shortTime(session.tracked())),
                Button.primary(BUTTON_PREFIX + ":" + FIX + base + ":" + (long) session.minimum() + ":" + (long) session.tracked(),
                        "Enter my real time"),
                Button.secondary(BUTTON_PREFIX + ":" + CANCEL + base, "Keep it running"));
    }

    /** From a {@code fix} button id; empty when the id is malformed. */
    public static Optional<Modal> form(String[] args) {
        if (args.length != 6) return Optional.empty();
        long minimum, tracked;
        try {
            Long.parseLong(args[3]);
            minimum = Long.parseLong(args[4]);
            tracked = Long.parseLong(args[5]);
        } catch (NumberFormatException malformed) { return Optional.empty(); }
        var hours = TextInput.create(HOURS, TextInputStyle.SHORT).setPlaceholder("e.g. 6").setRequiredRange(1, 4).build();
        var minutes = TextInput.create(MINUTES, TextInputStyle.SHORT).setPlaceholder("0-59").setRequired(false)
                .setRequiredRange(0, 2).build();
        String range = "Between " + shortTime(minimum) + " and " + shortTime(tracked);
        return Optional.of(Modal.create(MODAL_PREFIX + ":" + args[2] + ":" + args[3], "How long did you study?")
                .addComponents(Label.of("Hours", range, hours), Label.of("Minutes", "Leave empty for 0", minutes)).build());
    }

    /** Whole hours and minutes, in seconds; the service checks the range against the live session. */
    public static double parse(String hours, String minutes) {
        try {
            long h = Long.parseLong(hours.strip());
            long m = minutes == null || minutes.isBlank() ? 0 : Long.parseLong(minutes.strip());
            if (h < 0 || m < 0 || m > 59) throw new InputFailure("Use whole hours and 0-59 minutes.");
            return h * 3600.0 + m * 60.0;
        } catch (NumberFormatException notNumber) {
            throw new InputFailure("Use whole numbers, for example 6 hours and 30 minutes.");
        }
    }

    /**
     * Ends the session with the user's answer, closes its menu and posts the recap. Runs on a command worker after
     * the interaction was acknowledged; a break still open is closed now.
     */
    public static void finish(IReplyCallback event, String userId, long sessionStart, OptionalDouble reported) {
        var session = TimerSessionService.getTimerDataOrThrow(userId).getSessionData();
        String channelId = session.getChannelID(), messageId = session.getMessageID();
        String recap = TimerSessionService.stopSession(userId, event, true, new Confirmation(sessionStart, reported));
        SessionMenuManager.closeMenu(event.getJDA(), userId, channelId, messageId);
        event.getHook().editOriginalEmbeds(Embeds.of("✅", "Session ended", reported.isPresent()
                ? "Saved with your corrected study time." : "Saved with the full tracked time.").build()).setComponents().queue();
        // The ping lives in the message content; mentions inside embeds never notify.
        event.getHook().sendMessage("<@" + userId + ">").addEmbeds(SessionEmbeds.concluded(recap, event.getUser()))
                .setAllowedMentions(List.of()).mentionUsers(userId).queue();
    }

    /** "12h 5m" for button labels (80 characters at most). */
    static String shortTime(double seconds) {
        long minutes = (long) (seconds / 60);
        return minutes / 60 + "h " + minutes % 60 + "m";
    }
}
