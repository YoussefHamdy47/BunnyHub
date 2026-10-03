package org.bunnys.bunnynexus.timers.services;

import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.bson.conversions.Bson;
import org.bunnys.bunnynexus.timers.buttons.LongSessionPrompt;
import org.bunnys.bunnynexus.timers.engine.LevelEngine;
import org.bunnys.database.models.timers.*;
import org.bunnys.database.models.user.BunnyUser;
import org.bunnys.handler.database.DB;
import org.bunnys.handler.utils.InteractionErrors.InputFailure;
import org.bunnys.handler.utils.InteractionErrors.StateFailure;
import org.junit.jupiter.api.Test;
import java.util.Date;
import java.util.OptionalDouble;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LongSessionTest {
    static final long HOUR = 3_600_000;

    /** CS-101 then MATH-2, with 2 h already logged to CS-101 at the switch; the timer ran {@code hours}. */
    static TimerData forgotten(long hours) {
        var timer = new TimerData();
        var account = new Account();
        account.setUserID("123");
        account.setLifetimeTime(0);
        timer.setAccount(account);
        timer.getCurrentSemester().setSemesterName("Fall");
        timer.getCurrentSemester().setLongestSession(3 * 3600);
        for (String code : new String[]{"CS-101", "MATH-2"}) {
            var subject = new Subject();
            subject.setSubjectCode(code);
            subject.setTotalStudyTime(0.0);
            timer.getCurrentSemester().getSemesterSubjects().add(subject);
        }
        timer.getCurrentSemester().getSemesterSubjects().getFirst().setTotalStudyTime(2 * 3600.0);
        timer.getSessionData().setSessionStartTime(new Date(System.currentTimeMillis() - hours * HOUR));
        timer.getSessionData().setSessionTopic("MATH-2");
        timer.getSessionData().setSessionTime(2 * 3600);
        return timer;
    }

    @Test void onlySessionsOfTenHoursOrMoreNeedConfirming() {
        try (var db = mockStatic(DB.class)) {
            var shortOne = forgotten(9);
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(shortOne);
            assertTrue(TimerSessionService.longSession("123").isEmpty());
            var longOne = forgotten(48);
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(longOne);
            var found = TimerSessionService.longSession("123").orElseThrow();
            assertEquals(48 * 3600, found.tracked(), 5);
            assertEquals(2 * 3600, found.minimum());
            assertEquals(longOne.getSessionData().getSessionStartTime().getTime(), found.sessionStart());
        }
    }

    @Test void correctedTimeDrivesTotalsRecordsAndRewards() {
        var timer = forgotten(48);
        long start = timer.getSessionData().getSessionStartTime().getTime();
        var user = new BunnyUser();
        var interaction = mock(IReplyCallback.class, RETURNS_DEEP_STUBS);
        try (var db = mockStatic(DB.class); var store = mockStatic(TimerStore.class)) {
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(timer);
            db.when(() -> DB.findOne(eq(BunnyUser.class), eq("BunnyUsers"), any(Bson.class))).thenReturn(user);
            String recap = TimerSessionService.stopSession("123", interaction, true,
                    new TimerSessionService.Confirmation(start, OptionalDouble.of(5 * 3600)));
            var semester = timer.getCurrentSemester();
            assertEquals(5 * 3600, semester.getSemesterTime());
            assertEquals(5 * 3600, timer.getAccount().getLifetimeTime());
            assertEquals(5 * 3600, semester.getLongestSession());
            // 2 h were already on CS-101; only the other 3 h go to the subject being studied at the end.
            assertEquals(2 * 3600, semester.getSemesterSubjects().get(0).getTotalStudyTime());
            assertEquals(3 * 3600, semester.getSemesterSubjects().get(1).getTotalStudyTime());
            // The 43 h the user did not study are dropped, not turned into break time.
            assertEquals(0, semester.getTotalBreakTime(), 1);
            long expected = LevelEngine.calculateXP(LevelEngine.rewardedMinutes(java.util.List.of(5 * 3600.0)));
            assertTrue(recap.contains("XP & RP Earned: " + String.format("%,d", expected)), recap);
            assertTrue(recap.contains("5 hours"), recap);
            assertTrue(recap.contains("you corrected it"), recap);

            store.verify(() -> TimerStore.saveProgress("123", user, timer));
        }
    }

    @Test void confirmationsAreBoundToTheirSession() {
        var timer = forgotten(20);
        try (var db = mockStatic(DB.class); var store = mockStatic(TimerStore.class)) {
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(timer);
            db.when(() -> DB.findOne(eq(BunnyUser.class), eq("BunnyUsers"), any(Bson.class))).thenReturn(new BunnyUser());
            var interaction = mock(IReplyCallback.class, RETURNS_DEEP_STUBS);
            long otherSession = timer.getSessionData().getSessionStartTime().getTime() - HOUR;
            assertThrows(StateFailure.class, () -> TimerSessionService.stopSession("123", interaction, true,
                    new TimerSessionService.Confirmation(otherSession, OptionalDouble.empty())));
            store.verifyNoInteractions();
            assertNotNull(timer.getSessionData().getSessionStartTime());
        }
    }

    @Test void reportedTimeMustFitBetweenLoggedAndTracked() {
        var clock = new SessionClock(20 * 3600, 0, 0, 20 * 3600);
        double logged = 2 * 3600;
        assertEquals(20 * 3600, TimerSessionService.studyTime(clock, logged, OptionalDouble.empty()));
        assertEquals(6 * 3600, TimerSessionService.studyTime(clock, logged, OptionalDouble.of(6 * 3600)));
        // Minute-rounded entries at either end are accepted and clamped.
        assertEquals(20 * 3600, TimerSessionService.studyTime(clock, logged, OptionalDouble.of(20 * 3600 + 30)));
        assertEquals(logged, TimerSessionService.studyTime(clock, logged, OptionalDouble.of(logged - 30)));
        assertThrows(InputFailure.class, () -> TimerSessionService.studyTime(clock, logged, OptionalDouble.of(21 * 3600)));
        assertThrows(InputFailure.class, () -> TimerSessionService.studyTime(clock, logged, OptionalDouble.of(3600)));
        assertThrows(InputFailure.class, () -> TimerSessionService.studyTime(clock, logged, OptionalDouble.of(-1)));
        assertThrows(InputFailure.class, () -> TimerSessionService.studyTime(clock, logged, OptionalDouble.of(Double.NaN)));
    }

    @Test void promptControlsFitDiscordAndTheFormParsesHoursAndMinutes() {
        String user = "9223372036854775807";
        var session = new TimerSessionService.LongSession(1_790_000_000_000L, 48 * 3600 + 125, 2 * 3600);
        var buttons = LongSessionPrompt.controls(user, session).getComponents().stream().map(c -> c.asButton()).toList();
        assertEquals("Yes, I studied 48h 2m", buttons.get(0).getLabel());
        for (var button : buttons) assertTrue(button.getCustomId().length() <= 100, button.getCustomId());
        var form = LongSessionPrompt.form(buttons.get(1).getCustomId().split(":", -1)).orElseThrow();
        assertEquals(LongSessionPrompt.MODAL_PREFIX + ":" + user + ":1790000000000", form.getId());
        assertTrue(LongSessionPrompt.form(new String[]{"session_long", "fix", user, "x", "0", "0"}).isEmpty());

        assertEquals(6 * 3600 + 30 * 60, LongSessionPrompt.parse(" 6 ", "30"));
        assertEquals(20 * 3600, LongSessionPrompt.parse("20", ""));
        assertEquals(20 * 3600, LongSessionPrompt.parse("20", null));
        assertThrows(InputFailure.class, () -> LongSessionPrompt.parse("six", "0"));
        assertThrows(InputFailure.class, () -> LongSessionPrompt.parse("6", "75"));
        assertThrows(InputFailure.class, () -> LongSessionPrompt.parse("-1", "0"));
    }
}
