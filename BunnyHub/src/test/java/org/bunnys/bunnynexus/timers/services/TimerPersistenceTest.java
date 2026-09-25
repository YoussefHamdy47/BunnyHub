package org.bunnys.bunnynexus.timers.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.hooks.IEventManager;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.bson.conversions.Bson;
import org.bunnys.database.models.timers.*;
import org.bunnys.database.models.user.BunnyUser;
import org.bunnys.handler.database.DB;
import org.junit.jupiter.api.Test;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TimerPersistenceTest {
    private TimerData timer() {
        TimerData timer = new TimerData();
        Account account = new Account();
        account.setUserID("123");
        account.setLifetimeTime(7200);
        timer.setAccount(account);
        timer.getCurrentSemester().setSemesterName("Fall");
        timer.getCurrentSemester().setSemesterTime(3600);
        Subject subject = new Subject();
        subject.setSubjectCode("CS-101");
        subject.setTotalStudyTime(0.0);
        timer.getCurrentSemester().getSemesterSubjects().add(subject);
        return timer;
    }

    @Test void archiveKeepsLifetimeTimeAndPersistsHistoryBeforeNotifications() {
        TimerData timer = timer();
        Semester archive = timer.getCurrentSemester();
        BunnyUser user = new BunnyUser();
        var interaction = mock(IReplyCallback.class);
        var jda = mock(JDA.class);
        var events = mock(IEventManager.class);
        when(interaction.getJDA()).thenReturn(jda);
        when(jda.getEventManager()).thenReturn(events);
        try (var db = mockStatic(DB.class)) {
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(timer);
            db.when(() -> DB.findOne(eq(BunnyUser.class), eq("BunnyUsers"), any(Bson.class))).thenReturn(user);
            db.when(() -> DB.saveProgress("123", user, timer, archive)).thenAnswer(invocation -> {
                verifyNoInteractions(events);
                return null;
            });
            TimerAccountService.endSemester("123", interaction, timer.getRevision());
            assertEquals(7200, timer.getAccount().getLifetimeTime());
            assertSame(archive, timer.getAccount().getLongestSemester());
            assertNull(timer.getCurrentSemester().getSemesterName());
            db.verify(() -> DB.saveProgress("123", user, timer, archive));
            verify(events, atLeastOnce()).handle(any());
        }
    }

    @Test void activeSessionPreventsArchival() {
        TimerData timer = timer();
        timer.getSessionData().setSessionStartTime(new Date());
        try (var db = mockStatic(DB.class)) {
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(timer);
            db.when(() -> DB.findOne(eq(BunnyUser.class), eq("BunnyUsers"), any(Bson.class))).thenReturn(new BunnyUser());
            assertThrows(IllegalStateException.class, () -> TimerAccountService.endSemester("123", mock(IReplyCallback.class), timer.getRevision()));
            assertEquals("Fall", timer.getCurrentSemester().getSemesterName());
        }
    }

    @Test void changedSemesterCannotBeArchivedUsingEarlierConfirmation() {
        var timer = timer();
        timer.setRevision(2L);
        var semester = timer.getCurrentSemester();
        var user = new BunnyUser();
        var interaction = mock(IReplyCallback.class);
        try (var db = mockStatic(DB.class)) {
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(timer);
            db.when(() -> DB.findOne(eq(BunnyUser.class), eq("BunnyUsers"), any(Bson.class))).thenReturn(user);
            assertThrows(org.bunnys.handler.utils.InteractionErrors.StateFailure.class,
                    () -> TimerAccountService.endSemester("123", interaction, 1L));
            assertSame(semester, timer.getCurrentSemester());
            db.verify(() -> DB.saveProgress(anyString(), any(BunnyUser.class), any(TimerData.class), any(Semester.class)), never());
            verifyNoInteractions(interaction);
        }
    }

    @Test void startUsesHyphenatedSubjectAndResetsOldBreakState() {
        TimerData timer = timer();
        timer.getSessionData().getSessionBreaks().setSessionBreakTime(100);
        try (var db = mockStatic(DB.class)) {
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(timer);
            TimerSessionService.startSession("123", "456", "789", "DM", "CS-101 - Intro");
            assertEquals(1, timer.getCurrentSemester().getSemesterSubjects().getFirst().getTimesStudied());
            assertEquals(0, timer.getSessionData().getSessionBreaks().getSessionBreakTime());
            assertEquals("456", timer.getSessionData().getMessageID());
            assertEquals(1, timer.getCurrentSemester().getSessionStartTimes().size());
            assertThrows(IllegalStateException.class,
                    () -> TimerSessionService.startSession("123", "999", "789", "DM", "CS-101 - Intro"));
        }
    }

    @Test void stoppingCreditsOnlyUnallocatedTimeAndCommitsBothDocuments() {
        TimerData timer = timer();
        timer.getSessionData().setSessionStartTime(new Date(System.currentTimeMillis() - 600_000));
        timer.getSessionData().setSessionTopic("CS-101 - Intro");
        timer.getSessionData().setSessionTime(300);
        timer.getSessionData().getSessionBreaks().setSessionBreakTime(60);
        BunnyUser user = new BunnyUser();
        IReplyCallback interaction = mock(IReplyCallback.class, RETURNS_DEEP_STUBS);
        try (var db = mockStatic(DB.class)) {
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(timer);
            db.when(() -> DB.findOne(eq(BunnyUser.class), eq("BunnyUsers"), any(Bson.class))).thenReturn(user);
            TimerSessionService.stopSession("123", interaction);
            assertEquals(240, timer.getCurrentSemester().getSemesterSubjects().getFirst().getTotalStudyTime(), 2);
            assertEquals(7740, timer.getAccount().getLifetimeTime(), 2);
            assertNull(timer.getSessionData().getSessionStartTime());
            db.verify(() -> DB.saveProgress("123", user, timer));
        }
    }

    @Test void switchingSubjectsAllocatesElapsedTimeOnlyOnce() {
        TimerData timer = timer();
        Subject second = new Subject();
        second.setSubjectCode("MATH-2");
        timer.getCurrentSemester().getSemesterSubjects().add(second);
        timer.getSessionData().setSessionStartTime(new Date(System.currentTimeMillis() - 600_000));
        timer.getSessionData().setSessionTopic("CS-101 - Intro");
        timer.getSessionData().getSubjectsStudied().add("CS-101");
        try (var db = mockStatic(DB.class)) {
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(timer);
            TimerSessionService.changeSubject("123", "MATH-2 - Mathematics");
            assertEquals(600, timer.getCurrentSemester().getSemesterSubjects().getFirst().getTotalStudyTime(), 2);
            assertEquals(1, second.getTimesStudied());
            TimerSessionService.changeSubject("123", "CS-101 - Intro");
            assertEquals(600, timer.getCurrentSemester().getSemesterSubjects().getFirst().getTotalStudyTime(), 2);
            assertEquals(0, second.getTotalStudyTime(), 2);
        }
    }

    @Test void resumeAfterClockRollbackNeverSubtractsRecordedBreakTime() {
        var timer = timer();
        timer.getSessionData().setSessionStartTime(new Date(System.currentTimeMillis() - 600_000));
        timer.getSessionData().getSessionBreaks().setSessionBreakTime(60);
        timer.getSessionData().getSessionBreaks().setSessionBreakStart(new Date(System.currentTimeMillis() + 600_000));
        try (var db = mockStatic(DB.class)) {
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(timer);
            assertEquals(0, TimerSessionService.unpauseSession("123"));
            assertEquals(60, timer.getSessionData().getSessionBreaks().getSessionBreakTime());
            assertNull(timer.getSessionData().getSessionBreaks().getSessionBreakStart());
            db.verify(() -> DB.save(eq(TimerData.class), eq("TimerData"), any(Bson.class), eq(timer)));
        }
    }

    @Test void telemetryStillShowsPausedWhenBreakStartIsAheadOfClock() {
        var timer = timer();
        timer.getSessionData().setSessionStartTime(new Date(System.currentTimeMillis() - 600_000));
        timer.getSessionData().setSessionTopic("CS-101");
        timer.getSessionData().getSessionBreaks().setSessionBreakStart(new Date(System.currentTimeMillis() + 600_000));
        try (var db = mockStatic(DB.class)) {
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(timer);
            String description = TimerSessionService.getTelemetryEmbed("123").getDescription();
            assertTrue(description.contains("Currently on a break"));
            assertFalse(description.contains("Telemetry feed active and recording"));
        }
    }

    @Test void switchingAfterClockRollbackPreservesAlreadyAllocatedTime() {
        var timer = timer();
        var second = new Subject(); second.setSubjectCode("MATH-2");
        timer.getCurrentSemester().getSemesterSubjects().add(second);
        timer.getCurrentSemester().getSemesterSubjects().getFirst().setTotalStudyTime(600.0);
        timer.getSessionData().setSessionStartTime(new Date(System.currentTimeMillis() + 600_000));
        timer.getSessionData().setSessionTopic("CS-101");
        timer.getSessionData().setSessionTime(600);
        try (var db = mockStatic(DB.class)) {
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(timer);
            TimerSessionService.changeSubject("123", "MATH-2");
            assertEquals(600, timer.getSessionData().getSessionTime());
            // Simulate the clock catching up, still below the previously credited watermark.
            timer.getSessionData().setSessionStartTime(new Date(System.currentTimeMillis() - 300_000));
            TimerSessionService.changeSubject("123", "CS-101");
            assertEquals(0, second.getTotalStudyTime());
            assertEquals(600, timer.getCurrentSemester().getSemesterSubjects().getFirst().getTotalStudyTime());
        }
    }

    @Test void stoppingAfterClockRollbackKeepsSemesterAndSubjectTotalsConsistent() {
        var timer = timer();
        timer.getCurrentSemester().getSemesterSubjects().getFirst().setTotalStudyTime(600.0);
        timer.getSessionData().setSessionStartTime(new Date(System.currentTimeMillis() + 600_000));
        timer.getSessionData().setSessionTopic("CS-101");
        timer.getSessionData().setSessionTime(600);
        var user = new BunnyUser();
        var interaction = mock(IReplyCallback.class, RETURNS_DEEP_STUBS);
        try (var db = mockStatic(DB.class)) {
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(timer);
            db.when(() -> DB.findOne(eq(BunnyUser.class), eq("BunnyUsers"), any(Bson.class))).thenReturn(user);
            TimerSessionService.stopSession("123", interaction);
            assertEquals(600, timer.getCurrentSemester().getSemesterSubjects().getFirst().getTotalStudyTime());
            assertEquals(4200, timer.getCurrentSemester().getSemesterTime());
            assertEquals(7800, timer.getAccount().getLifetimeTime());
            db.verify(() -> DB.saveProgress("123", user, timer));
        }
    }

    @Test void forgottenSessionKeepsItsTimeButCapsRewards() {
        TimerData timer = timer();
        timer.getSessionData().setSessionStartTime(new Date(System.currentTimeMillis() - 3L * 24 * 3_600_000));
        timer.getSessionData().setSessionTopic("CS-101 - Intro");
        BunnyUser user = new BunnyUser();
        IReplyCallback interaction = mock(IReplyCallback.class, RETURNS_DEEP_STUBS);
        try (var db = mockStatic(DB.class)) {
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(timer);
            db.when(() -> DB.findOne(eq(BunnyUser.class), eq("BunnyUsers"), any(Bson.class))).thenReturn(user);
            String recap = TimerSessionService.stopSession("123", interaction);
            long capped = org.bunnys.bunnynexus.timers.engine.LevelEngine.calculateXP(
                    TimerSessionService.MAX_REWARDED_SESSION_SECS / 60.0);
            assertTrue(recap.contains(String.format("%,d", capped)));
            assertTrue(recap.contains("capped"));
            assertEquals(3 * 24 * 3600, timer.getCurrentSemester().getLongestSession(), 5);
        }
    }

    @Test void firstSessionSetsBaselineWithoutAnnouncingARecord() {
        TimerData timer = timer();
        timer.getSessionData().setSessionStartTime(new Date(System.currentTimeMillis() - 60_000));
        timer.getSessionData().setSessionTopic("CS-101 - Intro");
        var interaction = mock(IReplyCallback.class);
        var jda = mock(JDA.class);
        var events = mock(IEventManager.class);
        when(interaction.getJDA()).thenReturn(jda);
        when(jda.getEventManager()).thenReturn(events);
        try (var db = mockStatic(DB.class)) {
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(timer);
            db.when(() -> DB.findOne(eq(BunnyUser.class), eq("BunnyUsers"), any(Bson.class))).thenReturn(new BunnyUser());
            TimerSessionService.stopSession("123", interaction);
            assertEquals(60, timer.getCurrentSemester().getLongestSession(), 2);
            verify(events, never()).handle(any(org.bunnys.bunnynexus.events.custom.RecordBrokenEvent.class));
        }
    }

    @Test void recoveryStopClosesAnOpenBreakInsteadOfRejectingIt() {
        TimerData timer = timer();
        timer.getSessionData().setSessionStartTime(new Date(System.currentTimeMillis() - 600_000));
        timer.getSessionData().setSessionTopic("CS-101 - Intro");
        timer.getSessionData().getSessionBreaks().setSessionBreakStart(new Date(System.currentTimeMillis() - 240_000));
        BunnyUser user = new BunnyUser();
        IReplyCallback interaction = mock(IReplyCallback.class, RETURNS_DEEP_STUBS);
        try (var db = mockStatic(DB.class)) {
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(timer);
            db.when(() -> DB.findOne(eq(BunnyUser.class), eq("BunnyUsers"), any(Bson.class))).thenReturn(user);
            assertThrows(IllegalStateException.class, () -> TimerSessionService.stopSession("123", interaction));
            TimerSessionService.stopSession("123", interaction, true);
            assertEquals(360, timer.getCurrentSemester().getSemesterSubjects().getFirst().getTotalStudyTime(), 2);
            assertEquals(240, timer.getCurrentSemester().getTotalBreakTime(), 2);
            assertNull(timer.getSessionData().getSessionStartTime());
        }
    }
}
