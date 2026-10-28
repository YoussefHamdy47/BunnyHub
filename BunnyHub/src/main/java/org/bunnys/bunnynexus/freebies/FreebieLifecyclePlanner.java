package org.bunnys.bunnynexus.freebies;

import org.bunnys.bunnynexus.freebies.FreebieLifecycle.End;
import org.bunnys.utils.BunnyLog;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Queues lifecycle jobs from housekeeping (and right after an offer ends or is stopped). Every step is an
 * idempotent insert keyed by the job ID, so the end trigger and housekeeping may plan the same offer at once.
 */
final class FreebieLifecyclePlanner {
    static final int OFFERS_PER_RUN = 50;
    /** The longest reminder setting: offers ending later than this cannot have a reminder due. */
    static final Duration REMINDER_WINDOW = Duration.ofHours(24);

    private final FreebieRepository repository;
    private final FreebieSubscriptions subscriptions;

    FreebieLifecyclePlanner(FreebieRepository repository, FreebieSubscriptions subscriptions) {
        this.repository = repository; this.subscriptions = subscriptions;
    }

    private FreebieLifecycleStore jobs() { return repository.lifecycle(); }

    record Run(int edits, int withoutMessage, int reminders) {}

    Run run(Instant now) {
        int edits = 0, without = 0;
        for (var candidate : jobs().endCandidates(now, OFFERS_PER_RUN)) {
            var end = FreebieLifecycle.endTrigger(candidate.offer(), candidate.ownerStopped(), now);
            if (end.isEmpty()) continue;
            var planned = ended(candidate.offer().id(), end.get(), now);
            edits += planned.queued(); without += planned.withoutMessage();
        }
        int reminders = reminders(now);
        if (edits + reminders > 0 || without > 0)
            BunnyLog.info("[Freebies] Queued " + edits + " ended-card edit(s) and " + reminders + " reminder(s)"
                    + (without > 0 ? "; " + without + " older alert(s) have no saved message to edit." : "."));
        return new Run(edits, without, reminders);
    }

    /**
     * The offer ended or was withdrawn: pending reminders are cancelled and every sent alert with a saved message
     * gets one edit job. The offer is only recorded as marked once no alert can still become sent.
     */
    FreebieLifecycleStore.Planned ended(String offerId, End end, Instant now) {
        jobs().cancelReminders(offerId, now);
        if (repository.announcements() != null) repository.announcements().touch(offerId, now); // Posted pages rebuild without its claim.
        // Snapshot before scanning SENT rows: a send can finish between the scan and a later open-count read.
        // If anything was in flight, another pass must pick up that final card even when it has now settled.
        long openBeforePlanning = jobs().openDeliveries(offerId);
        var planned = jobs().planEdits(offerId, end == End.WITHDRAWN, now);
        if (openBeforePlanning == 0) jobs().endMarked(offerId, planned.withoutMessage(), now);
        return planned;
    }

    /** One reminder per offer and channel, when the channel's section asks for one and it is due now. */
    int reminders(Instant now) {
        int queued = 0;
        for (var offer : jobs().endingWithin(now, REMINDER_WINDOW, OFFERS_PER_RUN)) {
            var wanted = subscriptions.withReminders(offer.store());
            if (wanted.isEmpty()) continue;
            for (var delivery : jobs().sentIn(offer.id(), wanted.keySet())) {
                int hours = wanted.get(delivery.getString("channelId")).reminderHours();
                Date posted = delivery.getDate("finishedAt");
                var verdict = FreebieLifecycle.reminder(offer, hours, delivery.getString("state"), delivery.getString("messageId"),
                        posted == null ? null : posted.toInstant(), now);
                if (verdict != FreebieLifecycle.Reminder.DUE) continue;
                String group = delivery.getString("announcementKey");
                boolean planned = group == null ? jobs().planReminder(offer.id(), delivery, hours, now)
                        : jobs().planGroupReminder(offer.id(), group, delivery, hours, offer.realEnd().orElseThrow(), now);
                if (planned) queued++;
            }
        }
        return queued;
    }
}
