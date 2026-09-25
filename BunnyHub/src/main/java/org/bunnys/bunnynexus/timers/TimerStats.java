package org.bunnys.bunnynexus.timers;

import org.bunnys.database.models.timers.Semester;
import org.bunnys.database.models.timers.Subject;
import org.bunnys.database.models.timers.TimerData;
import org.bunnys.database.models.user.BunnyUser;
import org.bunnys.bunnynexus.timers.engine.LevelEngine;
import org.bunnys.utils.AppDesign;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public record TimerStats(TimerData timerData, BunnyUser userData) {

    public double getTotalStudyTime() {
        if (timerData.getAccount() == null)
            return 0;
        return timerData.getAccount().getLifetimeTime() > 0 ? timerData.getAccount().getLifetimeTime() * 1000 : 0;
    }

    public double getSemesterTime() {
        if (timerData.getCurrentSemester() == null)
            return 0;
        return timerData.getCurrentSemester().getSemesterTime() > 0
                ? timerData.getCurrentSemester().getSemesterTime() * 1000
                : 0;
    }

    /** Completed sessions; a running session is recorded at start but has no time in the totals yet. */
    public int getSessionCount() {
        if (timerData.getCurrentSemester() == null || timerData.getCurrentSemester().getSessionStartTimes() == null)
            return 0;
        int recorded = timerData.getCurrentSemester().getSessionStartTimes().size();
        boolean running = timerData.getSessionData() != null && timerData.getSessionData().getSessionStartTime() != null;
        return Math.max(0, running ? recorded - 1 : recorded);
    }

    public double getAverageSessionTime() {
        double semTime = getSemesterTime();
        int count = getSessionCount();
        return (semTime > 0 && count > 0) ? semTime / count : 0;
    }

    public double getLongestSessionTime() {
        if (timerData.getCurrentSemester() == null)
            return 0;
        return timerData.getCurrentSemester().getLongestSession() > 0
                ? timerData.getCurrentSemester().getLongestSession() * 1000
                : 0;
    }

    public Semester getLongestSemester() {
        return timerData.getAccount() != null ? timerData.getAccount().getLongestSemester() : null;
    }

    public double getBreakTime() {
        if (timerData.getCurrentSemester() == null)
            return 0;
        return timerData.getCurrentSemester().getTotalBreakTime() > 0
                ? timerData.getCurrentSemester().getTotalBreakTime() * 1000
                : 0;
    }

    public int getBreakCount() {
        return timerData.getCurrentSemester() != null ? timerData.getCurrentSemester().getBreakCount() : 0;
    }

    public double getAverageBreakTime() {
        double bTime = getBreakTime();
        int bCount = getBreakCount();
        return (bTime > 0 && bCount > 0) ? bTime / bCount : 0;
    }

    public double getAverageTimeBetweenBreaks() {
        double sTime = getSemesterTime();
        int bCount = getBreakCount();
        return (sTime > 0 && bCount > 0) ? sTime / bCount : 0;
    }

    public int getSubjectCount() {
        if (timerData.getCurrentSemester() == null || timerData.getCurrentSemester().getSemesterSubjects() == null)
            return 0;
        return timerData.getCurrentSemester().getSemesterSubjects().size();
    }

    public List<Subject> getSubjectsInOrder() {
        if (getSubjectCount() == 0)
            return Collections.emptyList();

        return timerData.getCurrentSemester().getSemesterSubjects().stream()
                .sorted(Comparator.comparingInt(Subject::getTimesStudied).reversed())
                .collect(Collectors.toList());
    }

    public int getTotalTimesStudied() {
        if (getSubjectCount() == 0)
            return 0;
        return timerData.getCurrentSemester().getSemesterSubjects().stream()
                .mapToInt(Subject::getTimesStudied)
                .sum();
    }

    public double getAverageStudyTimePerSubject() {
        int totalTimes = getSubjectCount();
        return totalTimes > 0 ? getSemesterTime() / totalTimes : 0;
    }

    public int getSemesterLevel() {
        return timerData.getCurrentSemester() != null ? timerData.getCurrentSemester().getSemesterLevel() : 0;
    }

    public double getSemesterXP() {
        return timerData.getCurrentSemester() != null ? timerData.getCurrentSemester().getSemesterXP() : 0;
    }

    public int getAccountLevel() {
        return userData != null ? userData.getRank() : 0;
    }

    public long getAccountRP() {
        return userData != null ? userData.getRp() : 0;
    }

    public int percentageToNextRank() {
        if (userData == null)
            return 0;
        return (int) Math.round(((double) userData.getRp() / LevelEngine.rpRequired(userData.getRank())) * 100.0);
    }

    public int percentageToNextLevel() {
        if (timerData.getCurrentSemester() == null)
            return 0;
        double currentXP = timerData.getCurrentSemester().getSemesterXP();
        double requiredXP = LevelEngine.xpRequired(timerData.getCurrentSemester().getSemesterLevel());
        return (int) Math.round((currentXP / requiredXP) * 100.0);
    }

    public double getMsToNextLevel() {
        if (timerData.getCurrentSemester() == null)
            return 0;
        double xpLeft = LevelEngine.xpRequired(timerData.getCurrentSemester().getSemesterLevel())
                - timerData.getCurrentSemester().getSemesterXP();
        return LevelEngine.hoursRequired((long) Math.max(0, xpLeft)) * 60 * 60 * 1000;
    }

    public double getMsToNextRank() {
        if (userData == null)
            return 0;
        double rpLeft = LevelEngine.rpRequired(userData.getRank()) - userData.getRp();
        return LevelEngine.hoursRequired((long) Math.max(0, rpLeft)) * 60 * 60 * 1000;
    }

    public double GPA() {
        return userData != null ? userData.calculateCumulativeGPA() : 0.0;
    }

    /** The stored streak only changes when a session ends, so report a lapsed streak as broken. */
    public int getCurrentStreak() {
        return currentStreak(System.currentTimeMillis());
    }

    public int currentStreak(long now) {
        Semester semester = timerData.getCurrentSemester();
        if (semester == null || semester.getLastStreakUpdate() == null)
            return 0;
        var today = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC).toLocalDate();
        var last = semester.getLastStreakUpdate().toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        return java.time.temporal.ChronoUnit.DAYS.between(last, today) > 1 ? 0 : semester.getStreak();
    }

    /**
     * Average time of day of the given start times, as milliseconds after UTC midnight. Uses a
     * circular mean so sessions either side of midnight average to midnight, not noon.
     */
    public static long averageMillisOfDay(List<Long> startTimes) {
        final double day = 86_400_000.0;
        double sin = 0, cos = 0;
        for (long timestamp : startTimes) {
            double angle = 2 * Math.PI * (Math.floorMod(timestamp, 86_400_000L) / day);
            sin += Math.sin(angle);
            cos += Math.cos(angle);
        }
        double mean = Math.atan2(sin, cos);
        if (mean < 0) mean += 2 * Math.PI;
        return Math.round(mean / (2 * Math.PI) * day) % 86_400_000L;
    }

    public int getLongestStreak() {
        return timerData.getCurrentSemester() != null ? timerData.getCurrentSemester().getLongestStreak() : 0;
    }

    public String generateProgressBar(int percentageComplete) {
        int totalSegments = 3;
        int clampedPercentage = Math.min(Math.max(percentageComplete, 0), 100);
        int filledSegments = (int) Math.round((clampedPercentage / 100.0) * totalSegments);

        String left = filledSegments >= 1 ? AppDesign.Emojis.PROGRESS_BAR_LEFT_FULL
                : AppDesign.Emojis.PROGRESS_BAR_LEFT_EMPTY;
        String middle = filledSegments >= 2 ? AppDesign.Emojis.PROGRESS_BAR_MIDDLE_FULL
                : AppDesign.Emojis.PROGRESS_BAR_MIDDLE_EMPTY;
        String right = filledSegments >= 3 ? AppDesign.Emojis.PROGRESS_BAR_RIGHT_FULL
                : AppDesign.Emojis.PROGRESS_BAR_RIGHT_EMPTY;

        return left + middle + right;
    }
}