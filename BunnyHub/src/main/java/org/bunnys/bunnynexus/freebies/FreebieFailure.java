package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Why a channel did not receive an alert, and whether trying again can help. */
public enum FreebieFailure {
    /** Network error, timeout, Discord 5xx or rate limit. The request may have been delivered anyway. */
    TRANSIENT(true, true, "Discord or the network kept failing, even after several retries."),
    MISSING_PERMISSIONS(false, true, "I don't have permission to post there. I need **View Channel**, **Send Messages** and **Embed Links** in that channel."),
    CHANNEL_MISSING(false, true, "The alert channel was deleted or I can no longer see it."),
    BOT_REMOVED(false, false, "The bot is no longer in this server."),
    REJECTED(false, true, "Discord rejected the message.");

    public static final int MAX_ATTEMPTS = 5;
    private static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(30), Duration.ofMinutes(2),
            Duration.ofMinutes(10), Duration.ofMinutes(30));

    private final boolean retryable, notifyServer;
    private final String explanation;
    FreebieFailure(boolean retryable, boolean notifyServer, String explanation) {
        this.retryable = retryable; this.notifyServer = notifyServer; this.explanation = explanation;
    }
    public boolean retryable() { return retryable; }
    /** Only failures a server owner can see/act on are reported to them. */
    public boolean notifyServer() { return notifyServer; }
    public String explanation() { return explanation; }

    /** Delay before the next attempt, or null when {@code attemptsSoFar} used up the budget. */
    public static Duration backoff(int attemptsSoFar) {
        if (attemptsSoFar >= MAX_ATTEMPTS) return null;
        return BACKOFF.get(Math.min(Math.max(attemptsSoFar, 1), BACKOFF.size()) - 1);
    }

    public static FreebieFailure classify(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null)
            cause = cause.getCause();
        if (cause instanceof InsufficientPermissionException) return MISSING_PERMISSIONS;
        if (cause instanceof ErrorResponseException response) {
            if (response.isServerError()) return TRANSIENT;
            ErrorResponse code = response.getErrorResponse();
            return switch (code) {
                case MISSING_PERMISSIONS, MISSING_ACCESS -> MISSING_PERMISSIONS;
                case UNKNOWN_CHANNEL -> CHANNEL_MISSING;
                case UNKNOWN_GUILD -> BOT_REMOVED;
                default -> response.getErrorCode() == 0 || response.getErrorCode() >= 130000 ? TRANSIENT : REJECTED;
            };
        }
        // Timeouts, I/O, rate limits and anything unexpected: the outcome is unknown, so retry (after checking history).
        return TRANSIENT;
    }
}
