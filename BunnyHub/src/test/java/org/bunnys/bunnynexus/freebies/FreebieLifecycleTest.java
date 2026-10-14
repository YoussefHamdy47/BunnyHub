package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.bunnys.bunnynexus.freebies.FreebieLifecycle.End;
import org.bunnys.bunnynexus.freebies.FreebieLifecycle.Outcome;
import org.bunnys.bunnynexus.freebies.FreebieLifecycle.Reminder;
import org.bunnys.bunnynexus.freebies.FreebieLifecycle.Skip;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Ended-trigger rules, reminder eligibility and job outcome classification. */
class FreebieLifecycleTest {
    static final Instant END = Instant.parse("2026-10-10T12:00:00Z");

    static FreebieOffer offer(FreebieOffer.State state, boolean gone, Optional<Instant> end, boolean estimated, boolean approved) {
        return new FreebieOffer("gamerpower:1", "1", FreebieStore.EPIC, "PC, Epic Games Store", "Game Giveaway", "desc", "1. Click",
                Optional.empty(), Optional.empty(), URI.create("https://www.gamerpower.com/open/x"), URI.create("https://www.gamerpower.com/x"),
                end, Instant.parse("2026-10-01T00:00:00Z"), state, Optional.empty(), Optional.empty(),
                approved ? Optional.of("333644367539470337") : Optional.empty(), true, gone, 1, estimated);
    }
    static FreebieOffer live() { return offer(FreebieOffer.State.APPROVED, false, Optional.of(END), false, true); }

    // ------------------------------------------------------------------ ended trigger

    @Test void ownerStopIsWithdrawnWhateverTheDates() {
        var stopped = offer(FreebieOffer.State.STOPPED, false, Optional.of(END), false, true);
        assertEquals(Optional.of(End.WITHDRAWN), FreebieLifecycle.endTrigger(stopped, true, END.minusSeconds(86_400)));
    }

    @Test void feedAbsenceAndARealEndDateEndTheCard() {
        var before = END.minusSeconds(60);
        assertEquals(Optional.empty(), FreebieLifecycle.endTrigger(live(), false, before), "still running");
        assertEquals(Optional.of(End.ENDED), FreebieLifecycle.endTrigger(live(), false, END), "real end reached");
        var gone = offer(FreebieOffer.State.COMPLETED, true, Optional.of(END), false, true);
        assertEquals(Optional.of(End.ENDED), FreebieLifecycle.endTrigger(gone, false, before), "missing from the feed");
        var autoStopped = offer(FreebieOffer.State.STOPPED, false, Optional.of(END), false, true);
        assertEquals(Optional.of(End.ENDED), FreebieLifecycle.endTrigger(autoStopped, false, END.plusSeconds(1)),
                "an automatic stop reads ended, not withdrawn");
    }

    @Test void anEstimatedEndDateNeverTriggersByItself() {
        var estimated = offer(FreebieOffer.State.COMPLETED, false, Optional.of(END), true, true);
        assertEquals(Optional.empty(), FreebieLifecycle.endTrigger(estimated, false, END.plusSeconds(86_400)));
        var stoppedOnEstimate = offer(FreebieOffer.State.STOPPED, false, Optional.of(END), true, true);
        assertEquals(Optional.empty(), FreebieLifecycle.endTrigger(stoppedOnEstimate, false, END.plusSeconds(86_400)));
        var goneToo = offer(FreebieOffer.State.COMPLETED, true, Optional.of(END), true, true);
        assertEquals(Optional.of(End.ENDED), FreebieLifecycle.endTrigger(goneToo, false, END.minusSeconds(60)));
    }

    @Test void neverPostedOffersHaveNoCardsToMark() {
        for (var state : new FreebieOffer.State[]{FreebieOffer.State.PENDING, FreebieOffer.State.REJECTED, FreebieOffer.State.EXPIRED})
            assertEquals(Optional.empty(), FreebieLifecycle.endTrigger(offer(state, true, Optional.of(END), false, false), false, END));
    }

    // ------------------------------------------------------------------ reminders

    Reminder remind(FreebieOffer offer, int hours, Instant posted, Instant now) {
        return FreebieLifecycle.reminder(offer, hours, "SENT", "900000000000000000", posted, now);
    }

    @Test void aReminderIsDueAtItsTimeForALongRunningAlert() {
        Instant posted = END.minus(Duration.ofDays(3)), at = END.minus(Duration.ofHours(24));
        assertEquals(Reminder.NOT_YET, remind(live(), 24, posted, at.minusSeconds(1)));
        assertEquals(Reminder.DUE, remind(live(), 24, posted, at));
        assertEquals(Reminder.DUE, remind(live(), 6, posted, END.minus(Duration.ofHours(6))));
        assertEquals(Reminder.OFF, remind(live(), 0, posted, at));
        assertEquals(Reminder.OFF, remind(live(), 12, posted, at), "only the offered settings");
    }

    @Test void anAlertPostedLessThanTwelveHoursBeforeTheReminderTimeGetsNone() {
        Instant at = END.minus(Duration.ofHours(6));
        assertEquals(Reminder.TOO_SOON_AFTER_ALERT, remind(live(), 6, at.minus(Duration.ofHours(12)).plusSeconds(1), at));
        assertEquals(Reminder.DUE, remind(live(), 6, at.minus(Duration.ofHours(12)), at), "exactly twelve hours is enough");
        assertEquals(Reminder.TOO_SOON_AFTER_ALERT, remind(live(), 24, null, END.minus(Duration.ofHours(24))), "unknown send time");
    }

    @Test void estimatedStoppedGoneOrUnapprovedOffersGetNoReminder() {
        Instant posted = END.minus(Duration.ofDays(3)), at = END.minus(Duration.ofHours(24));
        assertEquals(Reminder.NO_REAL_END, remind(offer(FreebieOffer.State.APPROVED, false, Optional.of(END), true, true), 24, posted, at));
        assertEquals(Reminder.NO_REAL_END, remind(offer(FreebieOffer.State.APPROVED, false, Optional.empty(), false, true), 24, posted, at));
        assertEquals(Reminder.NOT_LIVE, remind(offer(FreebieOffer.State.STOPPED, false, Optional.of(END), false, true), 24, posted, at));
        assertEquals(Reminder.NOT_LIVE, remind(offer(FreebieOffer.State.COMPLETED, true, Optional.of(END), false, true), 24, posted, at));
        assertEquals(Reminder.NOT_LIVE, remind(offer(FreebieOffer.State.APPROVED, false, Optional.of(END), false, false), 24, posted, at));
        assertEquals(Reminder.DUE, remind(offer(FreebieOffer.State.COMPLETED, false, Optional.of(END), false, true), 24, posted, at));
    }

    @Test void onlyAChannelWhoseAlertWasSentGetsAReminderAndNeverInTheLastHour() {
        Instant posted = END.minus(Duration.ofDays(3)), at = END.minus(Duration.ofHours(6));
        assertEquals(Reminder.NOT_SENT, FreebieLifecycle.reminder(live(), 6, "FAILED", "900000000000000000", posted, at));
        assertEquals(Reminder.NOT_SENT, FreebieLifecycle.reminder(live(), 6, "SENT", null, posted, at));
        assertEquals(Reminder.TOO_LATE, remind(live(), 6, posted, END.minus(Duration.ofMinutes(59))));
        assertEquals(Reminder.NOT_LIVE, remind(live(), 6, posted, END));
    }

    @Test void hoursLeftRoundsAndNeverSaysZero() {
        assertEquals(24, FreebieLifecycle.hoursLeft(END, END.minus(Duration.ofHours(24))));
        assertEquals(6, FreebieLifecycle.hoursLeft(END, END.minus(Duration.ofMinutes(5 * 60 + 40))));
        assertEquals(1, FreebieLifecycle.hoursLeft(END, END.minus(Duration.ofMinutes(10))));
    }

    // ------------------------------------------------------------------ outcomes

    static ErrorResponseException discord(ErrorResponse code, boolean serverError, int numeric) {
        var error = mock(ErrorResponseException.class);
        when(error.getErrorResponse()).thenReturn(code);
        when(error.isServerError()).thenReturn(serverError);
        when(error.getErrorCode()).thenReturn(numeric);
        return error;
    }

    @Test void missingTargetsAndAccessAreSkippedNeverRetried() {
        assertEquals(Skip.UNKNOWN_MESSAGE, FreebieLifecycle.classify(discord(ErrorResponse.UNKNOWN_MESSAGE, false, 10008)).skip());
        assertEquals(Skip.UNKNOWN_CHANNEL, FreebieLifecycle.classify(discord(ErrorResponse.UNKNOWN_CHANNEL, false, 10003)).skip());
        assertEquals(Skip.MISSING_ACCESS, FreebieLifecycle.classify(discord(ErrorResponse.MISSING_ACCESS, false, 50001)).skip());
        assertEquals(Skip.MISSING_ACCESS, FreebieLifecycle.classify(new CompletionException(discord(ErrorResponse.MISSING_PERMISSIONS, false, 50013))).skip());
        assertEquals(Skip.BOT_REMOVED, FreebieLifecycle.classify(discord(ErrorResponse.UNKNOWN_GUILD, false, 10004)).skip());
        var permission = mock(InsufficientPermissionException.class);
        when(permission.getPermission()).thenReturn(Permission.MESSAGE_HISTORY);
        assertEquals(Outcome.SKIP, FreebieLifecycle.classify(permission).outcome());
    }

    @Test void transientErrorsRetryWithBoundedBackoffAndOtherRejectionsFail() {
        assertEquals(Outcome.RETRY, FreebieLifecycle.classify(discord(ErrorResponse.SERVER_ERROR, true, 0)).outcome());
        assertEquals(Outcome.RETRY, FreebieLifecycle.classify(new CompletionException(new TimeoutException())).outcome());
        assertEquals(Outcome.FAIL, FreebieLifecycle.classify(discord(ErrorResponse.INVALID_FORM_BODY, false, 50035)).outcome());
        assertEquals(Optional.of(Duration.ofSeconds(30)), FreebieLifecycle.retryDelay(1));
        assertTrue(FreebieLifecycle.retryDelay(FreebieLifecycle.MAX_ATTEMPTS).isEmpty(), "attempts are bounded");
    }
}
