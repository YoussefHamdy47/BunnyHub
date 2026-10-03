package org.bunnys.bunnynexus.timers.services;

import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.bson.conversions.Bson;
import org.bunnys.bunnynexus.timers.engine.LevelEngine;
import org.bunnys.database.models.timers.*;
import org.bunnys.database.models.user.BunnyUser;
import org.bunnys.handler.database.DB;
import org.junit.jupiter.api.Test;
import java.util.Date;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudyStretchTest {
    static final long MINUTE = 60_000, HOUR = 60 * MINUTE;

    static TimerData session(long startedAgo) {
        var timer = new TimerData();
        var account = new Account();
        account.setUserID("123");
        timer.setAccount(account);
        timer.getCurrentSemester().setSemesterName("Fall");
        var subject = new Subject();
        subject.setSubjectCode("CS-101");
        subject.setTotalStudyTime(0.0);
        timer.getCurrentSemester().getSemesterSubjects().add(subject);
        timer.getSessionData().setSessionStartTime(new Date(System.currentTimeMillis() - startedAgo));
        timer.getSessionData().setSessionTopic("CS-101");
        return timer;
    }

    @Test void onlyARealBreakClosesAStretch() {
        try (var db = mockStatic(DB.class)) {
            // 3 h of study, then a 20-minute break: the 3 h stretch is closed.
            var rested = session(3 * HOUR + 20 * MINUTE);
            rested.getSessionData().getSessionBreaks().setSessionBreakStart(new Date(System.currentTimeMillis() - 20 * MINUTE));
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(rested);
            TimerSessionService.unpauseSession("123");
            assertEquals(1, rested.getSessionData().getStudyStretches().size());
            assertEquals(3 * 3600, rested.getSessionData().getStudyStretches().getFirst(), 2);

            // A 5-minute pause does not reset the curve, so it cannot be used to farm the full rate.
            var quick = session(3 * HOUR + 5 * MINUTE);
            quick.getSessionData().getSessionBreaks().setSessionBreakStart(new Date(System.currentTimeMillis() - 5 * MINUTE));
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(quick);
            TimerSessionService.unpauseSession("123");
            assertTrue(quick.getSessionData().getStudyStretches().isEmpty());
        }
    }

    @Test void laterStretchesOnlyCountTheirOwnStudy() {
        try (var db = mockStatic(DB.class)) {
            // Stretch 1 (2 h) was closed earlier; 1.5 h more study, then a 30-minute break.
            var timer = session(2 * HOUR + 30 * MINUTE + 90 * MINUTE + 30 * MINUTE);
            timer.getSessionData().getSessionBreaks().setSessionBreakTime(30 * 60);
            timer.getSessionData().getStudyStretches().add(2 * 3600.0);
            timer.getSessionData().getSessionBreaks().setSessionBreakStart(new Date(System.currentTimeMillis() - 30 * MINUTE));
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(timer);
            TimerSessionService.unpauseSession("123");
            assertEquals(List.of(2 * 3600.0, 90 * 60.0), timer.getSessionData().getStudyStretches().stream()
                    .map(s -> (double) Math.round(s)).toList());
        }
    }

    @Test void restedSessionsEarnMoreThanOneUnbrokenGrind() {
        var interaction = mock(IReplyCallback.class, RETURNS_DEEP_STUBS);
        try (var db = mockStatic(DB.class); var store = mockStatic(TimerStore.class)) {
            db.when(() -> DB.findOne(eq(BunnyUser.class), eq("BunnyUsers"), any(Bson.class))).thenReturn(new BunnyUser());
            // 8 h of study as four rested 2 h stretches (breaks recorded), versus 8 h straight.
            var rested = session(8 * HOUR + 3 * 20 * MINUTE);
            rested.getSessionData().getSessionBreaks().setSessionBreakTime(3 * 20 * 60);
            rested.getSessionData().getStudyStretches().addAll(List.of(7200.0, 7200.0, 7200.0));
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(rested);
            String restedRecap = TimerSessionService.stopSession("123", interaction);
            var straight = session(8 * HOUR);
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(straight);
            String recap = TimerSessionService.stopSession("123", interaction);

            assertTrue(restedRecap.contains(String.format("%,d", LevelEngine.calculateXP(8 * 60))), restedRecap);
            assertFalse(restedRecap.contains("earn less"), restedRecap);
            assertTrue(recap.contains(String.format("%,d", LevelEngine.calculateXP(LevelEngine.rewardedMinutes(List.of(8 * 3600.0))))), recap);
            assertTrue(recap.contains("A break of 15 minutes or more restores the full rate"), recap);
            // Both record the same study time; only the reward differs.
            assertEquals(rested.getCurrentSemester().getSemesterTime(), straight.getCurrentSemester().getSemesterTime(), 2);
            assertTrue(rested.getSessionData().getStudyStretches().isEmpty(), "stretches are cleared when the session ends");
            store.verify(() -> TimerStore.saveProgress(eq("123"), any(BunnyUser.class), same(straight)));
        }
    }

    @Test void aCorrectedTimeIsTakenFromTheEnd() {
        assertEquals(List.of(7200.0, 3600.0, 0.0), SessionProgress.stretches(List.of(7200.0, 7200.0), 3 * 3600));
        assertEquals(List.of(7200.0, 7200.0, 1800.0), SessionProgress.stretches(List.of(7200.0, 7200.0), 4.5 * 3600));
        assertEquals(List.of(3600.0), SessionProgress.stretches(List.of(), 3600));
    }
}
