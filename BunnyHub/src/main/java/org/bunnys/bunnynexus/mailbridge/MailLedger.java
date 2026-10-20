package org.bunnys.bunnynexus.mailbridge;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the bridge has done with each Gmail message, keyed by Gmail's message ID. This is what makes processing
 * idempotent across polls and restarts. No email content is stored: a message is fetched from Gmail again
 * whenever its notification has to be (re)built.
 */
public interface MailLedger {
    enum Status {
        /** Recognised and waiting to be posted (or to be retried after a failure). */
        PENDING,
        /** A post was attempted and Discord's answer never arrived; checked against the channel before retrying. */
        SENDING,
        /** Discord confirmed the post. */
        SENT,
        /** Not a task notification, or from an unexpected sender. */
        IGNORED,
        /** Looked like a task notification but matched no category, or more than one. Never posted. */
        UNCLASSIFIED,
        /** Gave up after the maximum number of attempts. */
        FAILED
    }

    record Entry(String id, Status status, MailCategory category, Instant received, int attempts,
                 Instant nextAttempt, Optional<String> discordMessageId, String note, Instant updated) {
        Entry with(Status status, int attempts, Instant nextAttempt, Optional<String> discordMessageId, String note, Instant now) {
            return new Entry(id, status, category, received, attempts, nextAttempt, discordMessageId, note, now);
        }
    }

    /** The first-start cutoff: stored on the first call, returned unchanged after that. */
    Instant cutoff(Instant firstStart);

    Optional<Entry> find(String id);

    void save(Entry entry);

    /** PENDING and SENDING entries whose next attempt is due, oldest first. */
    List<Entry> due(Instant now, int limit);

    Map<Status, Long> counts();
}
