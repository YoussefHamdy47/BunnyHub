package org.bunnys.bunnynexus.timers.services;

import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.bson.conversions.Bson;
import org.bunnys.database.models.timers.*;
import org.bunnys.database.models.user.BunnyUser;
import org.bunnys.handler.database.DB;
import org.junit.jupiter.api.Test;
import java.util.Date;
import java.util.List;
import java.util.OptionalDouble;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionSubjectSplitTest {
    static final long MINUTE = 60_000;

    static TimerData session(long startedAgo, String... codes) {
        var timer = new TimerData();
        var account = new Account();
        account.setUserID("123");
        timer.setAccount(account);
        timer.getCurrentSemester().setSemesterName("Fall");
        for (String code : codes) {
            var subject = new Subject();
            subject.setSubjectCode(code);
            subject.setTotalStudyTime(0.0);
            timer.getCurrentSemester().getSemesterSubjects().add(subject);
        }
        timer.getSessionData().setSessionStartTime(new Date(System.currentTimeMillis() - startedAgo));
        timer.getSessionData().setSessionTopic(codes[0]);
        timer.getSessionData().getSubjectsStudied().add(codes[0]);
        return timer;
    }

    static void moveStartBack(TimerData timer, long millis) {
        var session = timer.getSessionData();
        session.setSessionStartTime(new Date(session.getSessionStartTime().getTime() - millis));
    }

    static String recap(SessionSplit split) {
        return SessionProgress.recap(0, new SessionClock(split.study, 0, 0, split.study), split.study,
                OptionalDouble.empty(), 0, split.subjects, 0, split.study / 60);
    }

    record SessionSplit(double study, List<SessionProgress.SubjectTime> subjects) {}

    @Test void endCardSplitsTheSessionBySubject() {
        var interaction = mock(IReplyCallback.class, RETURNS_DEEP_STUBS);
        try (var db = mockStatic(DB.class); var store = mockStatic(TimerStore.class)) {
            var timer = session(30 * MINUTE, "CS-101", "MATH-2");
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(timer);
            db.when(() -> DB.findOne(eq(BunnyUser.class), eq("BunnyUsers"), any(Bson.class))).thenReturn(new BunnyUser());

            TimerSessionService.changeSubject("123", "MATH-2 - Mathematics");
            moveStartBack(timer, 10 * MINUTE);
            String recap = TimerSessionService.stopSession("123", interaction);

            assertTrue(recap.contains("• Subject Breakdown:"), recap);
            int cs = recap.indexOf("**CS-101**: 30 minutes (75%)"), math = recap.indexOf("**MATH-2**: 10 minutes (25%)");
            assertTrue(cs > 0 && math > cs, recap);
            assertTrue(timer.getSessionData().getSubjectTimes().isEmpty(), "the split is cleared when the session ends");
            store.verify(() -> TimerStore.saveProgress(eq("123"), any(BunnyUser.class), same(timer)));
        }
    }

    @Test void returningToASubjectAddsToItsShare() {
        var timer = session(20 * MINUTE, "CS-101", "MATH-2");
        try (var db = mockStatic(DB.class)) {
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(timer);
            TimerSessionService.changeSubject("123", "MATH-2");
            moveStartBack(timer, 5 * MINUTE);
            TimerSessionService.changeSubject("123", "CS-101");
            moveStartBack(timer, 10 * MINUTE);
            TimerSessionService.changeSubject("123", "MATH-2");
        }
        var times = timer.getSessionData().getSubjectTimes();
        assertEquals(List.of("CS-101", "MATH-2"), timer.getSessionData().getSubjectsStudied());
        assertEquals(30 * 60, times.get(0), 2);
        assertEquals(5 * 60, times.get(1), 2);
    }

    @Test void aSingleSubjectGetsOneLine() {
        var timer = session(0, "CS-101");
        SessionProgress.addSubjectTime(timer.getSessionData(), "CS-101", 4500);
        var subjects = SessionProgress.subjectTimes(timer.getSessionData(), 4500);
        String recap = recap(new SessionSplit(4500, subjects));
        assertTrue(recap.contains("• Subject Studied: **CS-101** for 1 hour 15 minutes"), recap);
        assertFalse(recap.contains("Breakdown"), recap);
    }

    @Test void aSubjectLeftWithinASecondIsNotListed() {
        var timer = session(0, "CS-101", "MATH-2");
        timer.getSessionData().getSubjectsStudied().add("MATH-2");
        SessionProgress.addSubjectTime(timer.getSessionData(), "MATH-2", 600);
        var subjects = SessionProgress.subjectTimes(timer.getSessionData(), 600);
        assertEquals(List.of(new SessionProgress.SubjectTime("MATH-2", 600)), subjects);
    }

    @Test void studyFromBeforeTheSplitWasRecordedIsShownSeparately() {
        // A session started before the split existed: 20 minutes logged at a switch, then 10 on the current subject.
        var timer = session(0, "CS-101", "MATH-2");
        timer.getSessionData().getSubjectsStudied().add("MATH-2");
        SessionProgress.addSubjectTime(timer.getSessionData(), "MATH-2", 600);
        var subjects = SessionProgress.subjectTimes(timer.getSessionData(), 1800);
        assertEquals(List.of(new SessionProgress.SubjectTime(SessionProgress.UNSPLIT_STUDY, 1200),
                new SessionProgress.SubjectTime("MATH-2", 600)), subjects);
    }

    @Test void aCorrectedTimeShrinksTheCurrentSubject() {
        var interaction = mock(IReplyCallback.class, RETURNS_DEEP_STUBS);
        long hour = 60 * MINUTE;
        try (var db = mockStatic(DB.class); var store = mockStatic(TimerStore.class)) {
            var timer = session(2 * hour, "CS-101", "MATH-2");
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(timer);
            db.when(() -> DB.findOne(eq(BunnyUser.class), eq("BunnyUsers"), any(Bson.class))).thenReturn(new BunnyUser());
            TimerSessionService.changeSubject("123", "MATH-2");
            moveStartBack(timer, 10 * hour);
            long start = timer.getSessionData().getSessionStartTime().getTime();
            String recap = TimerSessionService.stopSession("123", interaction, false,
                    new TimerSessionService.Confirmation(start, OptionalDouble.of(3 * 3600)));
            assertTrue(recap.contains("**CS-101**: 2 hours (67%)"), recap);
            assertTrue(recap.contains("**MATH-2**: 1 hour (33%)"), recap);
            store.verify(() -> TimerStore.saveProgress(eq("123"), any(BunnyUser.class), same(timer)));
        }
    }
}
