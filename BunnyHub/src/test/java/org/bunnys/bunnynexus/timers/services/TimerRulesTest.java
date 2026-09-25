package org.bunnys.bunnynexus.timers.services;

import org.bunnys.database.models.timers.*;
import org.bunnys.database.models.user.BunnyUser;
import org.bunnys.bunnynexus.timers.TimerStats;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TimerRulesTest {
    @Test void hyphenatedCodesArePreservedAndUnknownTopicsRejected() {
        assertEquals("CS-101", SubjectTopics.code("cs-101 - Introduction - Part 1"));
        assertEquals("CS-101", SubjectTopics.code("cs-101"));
        assertThrows(IllegalArgumentException.class, () -> SubjectTopics.code("-"));
        assertThrows(IllegalArgumentException.class, () -> SubjectTopics.require(List.of(), "unknown"));
        Locale old = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("IT101", SubjectTopics.code("it101 - Introduction"));
        } finally { Locale.setDefault(old); }
    }

    @Test void streakCountsCalendarDaysInsteadOfElapsed24HourWindows() {
        TimerData timer = new TimerData();
        TimerSessionService.updateStreak(timer, Instant.parse("2026-01-01T23:50:00Z").toEpochMilli());
        TimerSessionService.updateStreak(timer, Instant.parse("2026-01-02T00:10:00Z").toEpochMilli());
        assertEquals(2, timer.getCurrentSemester().getStreak());
        TimerSessionService.updateStreak(timer, Instant.parse("2026-01-02T23:00:00Z").toEpochMilli());
        assertEquals(2, timer.getCurrentSemester().getStreak());
        TimerSessionService.updateStreak(timer, Instant.parse("2026-01-04T01:00:00Z").toEpochMilli());
        assertEquals(1, timer.getCurrentSemester().getStreak());
        assertEquals(2, timer.getCurrentSemester().getLongestStreak());
    }

    @Test void statsMatchProgressionThresholds() {
        TimerData timer = new TimerData();
        timer.getCurrentSemester().setSemesterXP(125);
        BunnyUser user = new BunnyUser();
        user.setRp(150);
        TimerStats stats = new TimerStats(timer, user);
        assertEquals(50, stats.percentageToNextLevel());
        assertEquals(50, stats.percentageToNextRank());
        assertEquals(125 / 2160.0 * 3600000, stats.getMsToNextLevel(), 0.001);
    }

    @Test void optionalLegacyFieldsRemainUsable() {
        TimerData timer = new TimerData();
        timer.setCurrentSemester(null);
        timer.setSessionData(null);
        timer.getSessionData().setSubjectsStudied(null);
        timer.getSessionData().setSessionBreaks(null);
        assertNotNull(timer.getCurrentSemester());
        assertNotNull(timer.getSessionData().getSessionBreaks());
        assertTrue(timer.getSessionData().getSubjectsStudied().isEmpty());
    }

    @Test void gpaIsWeightedAndExcludesPassAndWithdrawal() {
        BunnyUser user = new BunnyUser();
        user.setSubjects(List.of(subject("A", 3), subject("B", 1), subject("Pass", 20), subject("Withdraw", 20)));
        assertEquals(3.75, user.calculateCumulativeGPA());
    }

    @Test void subjectsValidateBeforePersistence() {
        var subject = subject("A", -1);
        assertThrows(IllegalArgumentException.class, () -> TimerSubjectService.validate(subject));
    }

    private Subject subject(String grade, int credits) {
        var subject = new Subject();
        subject.setSubjectCode("CS-101");
        subject.setSubjectName("Introduction");
        subject.setGrade(grade);
        subject.setCreditHours(credits);
        return subject;
    }

    @Test void lapsedStreakIsReportedAsBrokenWithoutWaitingForNextSession() {
        TimerData timer = new TimerData();
        TimerSessionService.updateStreak(timer, Instant.parse("2026-01-01T12:00:00Z").toEpochMilli());
        TimerSessionService.updateStreak(timer, Instant.parse("2026-01-02T12:00:00Z").toEpochMilli());
        var stats = new TimerStats(timer, new BunnyUser());
        assertEquals(2, stats.currentStreak(Instant.parse("2026-01-03T20:00:00Z").toEpochMilli()));
        assertEquals(0, stats.currentStreak(Instant.parse("2026-01-04T00:30:00Z").toEpochMilli()));
    }

    @Test void averageStartTimeWrapsAroundMidnight() {
        long lateEvening = Instant.parse("2026-01-01T23:00:00Z").toEpochMilli();
        long earlyMorning = Instant.parse("2026-01-03T01:00:00Z").toEpochMilli();
        long average = TimerStats.averageMillisOfDay(List.of(lateEvening, earlyMorning));
        assertTrue(average < 60_000 || average > 86_340_000, "expected about midnight, got " + average);
        assertEquals(10 * 3_600_000L, TimerStats.averageMillisOfDay(
                List.of(Instant.parse("2026-01-01T09:00:00Z").toEpochMilli(),
                        Instant.parse("2026-01-02T11:00:00Z").toEpochMilli())), 1000);
    }

    @Test void runningSessionIsNotCountedAsCompleted() {
        TimerData timer = new TimerData();
        timer.getCurrentSemester().getSessionStartTimes().addAll(List.of(1L, 2L));
        assertEquals(2, new TimerStats(timer, new BunnyUser()).getSessionCount());
        timer.getSessionData().setSessionStartTime(new Date());
        assertEquals(1, new TimerStats(timer, new BunnyUser()).getSessionCount());
    }
}
