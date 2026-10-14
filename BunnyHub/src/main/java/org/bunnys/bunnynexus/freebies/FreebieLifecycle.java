package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Rules for message lifecycle work after an alert was posted: marking the card ended, and the optional
 * "ending soon" reminder. Pure decisions, no I/O; {@link FreebieLifecycleStore} persists the jobs and
 * {@link FreebieLifecycleWorker} talks to Discord.
 */
final class FreebieLifecycle {
    private FreebieLifecycle() {}

    enum Kind { EDIT, REMIND }
    /** PENDING and SENDING are open; the rest are final. */
    enum State { PENDING, SENDING, DONE, SKIPPED, FAILED, CANCELLED }
    /** How the card is marked: the giveaway ended, or the owner withdrew it. */
    enum End { ENDED, WITHDRAWN }

    /** Why a job ended without doing its work. Never retried. */
    enum Skip {
        UNKNOWN_MESSAGE("The alert message was deleted, so there was nothing to update."),
        UNKNOWN_CHANNEL("The alert channel was deleted or is no longer visible to the bot."),
        MISSING_ACCESS("The bot lacks access or permissions in the alert channel."),
        BOT_REMOVED("The bot is no longer in this server."),
        NOT_OURS("The saved message is not the bot's alert card, so it was left alone."),
        HISTORY_UNAVAILABLE("An earlier attempt may have arrived and history cannot be checked, so nothing was posted again.");
        final String text;
        Skip(String text) { this.text = text; }
    }

    /** Why a job was withdrawn before its work was due or done. */
    enum Cancel {
        OFFER_ENDED("The giveaway ended or was stopped before the reminder."),
        SETTING_OFF("Reminders were turned off, or the channel no longer posts this launcher."),
        TOO_LATE("Less than an hour was left, so no reminder was posted.");
        final String text;
        Cancel(String text) { this.text = text; }
    }

    static final Set<Integer> REMINDER_HOURS = Set.of(6, 24);
    /** A game is never announced and then reminded about almost at once. */
    static final Duration MIN_GAP_AFTER_ALERT = Duration.ofHours(12);
    static final Duration MIN_LEFT = Duration.ofHours(1);
    static final int MAX_ATTEMPTS = FreebieFailure.MAX_ATTEMPTS;

    // ------------------------------------------------------------------ ended trigger

    /**
     * When the posted cards of an offer should be marked: the owner stopped it (withdrawn), it left the feed
     * (the existing two-missed-polls rule sets {@code gone}), or its real end date passed. An estimated-only end
     * date never triggers by itself.
     */
    static Optional<End> endTrigger(FreebieOffer offer, boolean ownerStopped, Instant now) {
        if (ownerStopped && offer.state() == FreebieOffer.State.STOPPED) return Optional.of(End.WITHDRAWN);
        if (offer.state() == FreebieOffer.State.PENDING || offer.state() == FreebieOffer.State.REJECTED
                || offer.state() == FreebieOffer.State.EXPIRED) return Optional.empty(); // Never posted.
        if (offer.gone()) return Optional.of(End.ENDED);
        return offer.realEnd().filter(end -> !now.isBefore(end)).map(end -> End.ENDED);
    }

    // ------------------------------------------------------------------ reminders

    enum Reminder { DUE, NOT_YET, OFF, NOT_LIVE, NO_REAL_END, NOT_SENT, TOO_SOON_AFTER_ALERT, TOO_LATE }

    /** Approved, still listed, not stopped, and before its real end. */
    static boolean live(FreebieOffer offer, Instant now) {
        return offer != null && offer.decidedBy().isPresent() && !offer.gone()
                && (offer.state() == FreebieOffer.State.APPROVED || offer.state() == FreebieOffer.State.COMPLETED)
                && offer.realEnd().map(now::isBefore).orElse(false);
    }

    /**
     * Whether a channel should get its reminder now. {@code postedAt} is when the original alert was recorded as
     * sent; the reminder time is the real end minus {@code hours}.
     */
    static Reminder reminder(FreebieOffer offer, int hours, String deliveryState, String messageId, Instant postedAt, Instant now) {
        if (!REMINDER_HOURS.contains(hours)) return Reminder.OFF;
        if (offer.realEnd().isEmpty()) return Reminder.NO_REAL_END;
        if (!live(offer, now)) return Reminder.NOT_LIVE;
        if (!"SENT".equals(deliveryState) || messageId == null) return Reminder.NOT_SENT;
        Instant end = offer.realEnd().get(), at = end.minus(Duration.ofHours(hours));
        if (now.isBefore(at)) return Reminder.NOT_YET;
        if (postedAt == null || Duration.between(postedAt, at).compareTo(MIN_GAP_AFTER_ALERT) < 0) return Reminder.TOO_SOON_AFTER_ALERT;
        if (Duration.between(now, end).compareTo(MIN_LEFT) < 0) return Reminder.TOO_LATE;
        return Reminder.DUE;
    }

    /** Whole hours left, rounded, at least one: "Ends in about N hours". */
    static long hoursLeft(Instant end, Instant now) {
        long minutes = Math.max(0, Duration.between(now, end).toMinutes());
        return Math.max(1, (minutes + 30) / 60);
    }

    // ------------------------------------------------------------------ outcomes

    enum Outcome { SKIP, RETRY, FAIL }
    record Verdict(Outcome outcome, Skip skip) {}

    /**
     * Unknown message/channel/guild and missing access end the job as skipped; 5xx, timeouts, rate limits and
     * anything unexpected retry with the alert backoff; any other definite Discord rejection fails.
     */
    static Verdict classify(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null)
            cause = cause.getCause();
        if (cause instanceof InsufficientPermissionException) return new Verdict(Outcome.SKIP, Skip.MISSING_ACCESS);
        if (cause instanceof ErrorResponseException response) {
            if (response.isServerError()) return new Verdict(Outcome.RETRY, null);
            return switch (response.getErrorResponse()) {
                case UNKNOWN_MESSAGE -> new Verdict(Outcome.SKIP, Skip.UNKNOWN_MESSAGE);
                case UNKNOWN_CHANNEL -> new Verdict(Outcome.SKIP, Skip.UNKNOWN_CHANNEL);
                case MISSING_ACCESS, MISSING_PERMISSIONS, CANNOT_SEND_TO_USER, UNKNOWN_USER -> new Verdict(Outcome.SKIP, Skip.MISSING_ACCESS);
                case UNKNOWN_GUILD -> new Verdict(Outcome.SKIP, Skip.BOT_REMOVED);
                default -> response.getErrorCode() == 0 || response.getErrorCode() >= 130000
                        ? new Verdict(Outcome.RETRY, null) : new Verdict(Outcome.FAIL, null);
            };
        }
        return new Verdict(Outcome.RETRY, null);
    }

    /** Delay before the next attempt, or empty when {@code attemptsSoFar} used the budget (the job then fails). */
    static Optional<Duration> retryDelay(int attemptsSoFar) {
        return Optional.ofNullable(FreebieFailure.backoff(attemptsSoFar));
    }
}
