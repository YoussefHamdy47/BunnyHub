package org.bunnys.bunnynexus.mailbridge;

import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Read-only access to the mailbox. Implemented by {@link GmailApi}; tests supply their own. */
public interface MailSource {
    /** Message IDs matching a Gmail search, newest first, at most {@code max}. */
    List<String> search(String query, int max) throws MailSourceException;

    /** One full message ({@code format=full}). */
    DataObject fetch(String id) throws MailSourceException;

    /** Why a mailbox call failed, and whether trying again later can help. */
    final class MailSourceException extends Exception {
        public enum Kind {
            /** The refresh token was revoked or expired, or the scope is wrong: needs a person to re-authorize. */
            AUTH,
            /** Network trouble, rate limiting or a Google outage: try again later. */
            TRANSIENT,
            /** This particular request can't succeed (for example the message was deleted). */
            PERMANENT
        }

        private final Kind kind;
        private final Optional<Instant> retryAfter;

        public MailSourceException(Kind kind, String message, Optional<Instant> retryAfter) {
            super(message);
            this.kind = kind;
            this.retryAfter = retryAfter;
        }

        public MailSourceException(Kind kind, String message) { this(kind, message, Optional.empty()); }

        public Kind kind() { return kind; }
        public Optional<Instant> retryAfter() { return retryAfter; }
    }
}
