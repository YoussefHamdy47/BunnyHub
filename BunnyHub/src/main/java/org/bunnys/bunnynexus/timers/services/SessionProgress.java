package org.bunnys.bunnynexus.timers.services;

import org.bunnys.bunnynexus.timers.engine.LevelEngine;
import org.bunnys.database.models.timers.Session;
import org.bunnys.database.models.timers.TimerData;
import org.bunnys.database.models.user.BunnyUser;
import org.bunnys.utils.Durations;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

/** What a finished session adds to the semester and account records. Pure in-memory updates; no I/O. */
final class SessionProgress {
    private SessionProgress() {}

    static void addToTotals(TimerData timerData, double breakSecs, double activeSecs) {
        var semester = timerData.getCurrentSemester();
        semester.setTotalBreakTime(semester.getTotalBreakTime() + breakSecs);
        semester.setSemesterTime(semester.getSemesterTime() + activeSecs);
        timerData.getAccount().setLifetimeTime(timerData.getAccount().getLifetimeTime() + activeSecs);
    }

    /** Applies earned points to the account RP track. */
    static LevelEngine.RankResult applyRank(BunnyUser user, long points) {
        var result = LevelEngine.checkRank(user.getRank(), user.getRp(), points);
        if (result.hasRankedUp()) {
            user.setRank(user.getRank() + result.addedLevels());
            user.setRp(result.remainingRP());
        } else {
            user.setRp(Math.addExact(user.getRp(), points));
        }
        return result;
    }

    /** Applies earned points to the semester XP track. */
    static LevelEngine.LevelResult applyLevel(TimerData timerData, long points) {
        var semester = timerData.getCurrentSemester();
        long currentXP = (long) semester.getSemesterXP();
        var result = LevelEngine.checkLevel(semester.getSemesterLevel(), currentXP, points);
        if (result.hasLeveledUp()) {
            semester.setSemesterLevel(semester.getSemesterLevel() + result.addedLevels());
            semester.setSemesterXP(result.remainingXP());
        } else {
            semester.setSemesterXP(currentXP + points);
        }
        return result;
    }

    /** Streaks count calendar days in UTC, independent of server timezone and session hour. */
    static void updateStreak(TimerData timerData, long now) {
        var semester = timerData.getCurrentSemester();
        var today = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate();
        Date lastUpdate = semester.getLastStreakUpdate();
        long days = lastUpdate == null ? Long.MAX_VALUE
                : ChronoUnit.DAYS.between(lastUpdate.toInstant().atZone(ZoneOffset.UTC).toLocalDate(), today);
        if (days > 1) semester.setStreak(1);
        else if (days == 1) semester.setStreak(semester.getStreak() + 1);
        if (days > 0) semester.setLastStreakUpdate(new Date(now));
        semester.setLongestStreak(Math.max(semester.getLongestStreak(), semester.getStreak()));
    }

    /**
     * The session's study split into unbroken stretches: the ones closed by real breaks, then the one running at the
     * end. When the user reported less than the timer tracked, the missing time is taken from the end, where a
     * forgotten timer's idle hours are.
     */
    static List<Double> stretches(List<Double> closed, double studySecs) {
        List<Double> stretches = new ArrayList<>();
        double left = studySecs;
        for (double stretch : closed) {
            double taken = Math.min(Math.max(0, stretch), left);
            stretches.add(taken);
            left -= taken;
        }
        stretches.add(Math.max(0, left));
        return stretches;
    }

    /** How long one subject was studied during a session, in seconds. */
    record SubjectTime(String code, double seconds) {}

    /** Shown for study a session logged before it recorded its per-subject split. */
    static final String UNSPLIT_STUDY = "Earlier subjects";

    /** Adds credited study time to the session's entry for {@code code} (an entry of the studied-subjects list). */
    static void addSubjectTime(Session session, String code, double seconds) {
        List<String> studied = session.getSubjectsStudied();
        List<Double> times = session.getSubjectTimes();
        int index = -1;
        for (int i = 0; i < studied.size() && index < 0; i++)
            if (studied.get(i) != null && studied.get(i).trim().equalsIgnoreCase(code)) index = i;
        if (index < 0) return;
        while (times.size() < studied.size()) times.add(0.0);
        times.set(index, times.get(index) + Math.max(0, seconds));
    }

    /**
     * The session's study split by subject, longest first. Subjects switched away from within a second are left out
     * unless nothing else was studied; study the session did not split (it began before the split was recorded) is
     * listed as {@link #UNSPLIT_STUDY}.
     */
    static List<SubjectTime> subjectTimes(Session session, double studySecs) {
        List<String> studied = session.getSubjectsStudied();
        List<Double> times = session.getSubjectTimes();
        List<SubjectTime> all = new ArrayList<>();
        double split = 0;
        for (int i = 0; i < studied.size(); i++) {
            double seconds = i < times.size() && times.get(i) != null ? times.get(i) : 0;
            split += seconds;
            all.add(new SubjectTime(studied.get(i).trim(), seconds));
        }
        if (!studied.isEmpty() && studySecs - split >= 1) all.add(new SubjectTime(UNSPLIT_STUDY, studySecs - split));
        List<SubjectTime> shown = all.stream().filter(entry -> entry.seconds() >= 1).toList();
        return (shown.isEmpty() ? all : shown).stream()
                .sorted(Comparator.comparingDouble(SubjectTime::seconds).reversed()).toList();
    }

    /** @param adjustedFrom the tracked study time, when the user corrected it to {@code studySecs} */
    static String recap(long startMs, SessionClock clock, double studySecs, OptionalDouble adjustedFrom, int breakCount,
                        List<SubjectTime> subjects, long points, double rewardedMinutes) {
        StringBuilder recap = new StringBuilder();
        recap.append("• Start Time: <t:").append(startMs / 1000).append(":F>\n")
                .append("• Total Time Elapsed: ").append(Durations.formatSeconds(clock.elapsed())).append("\n")
                .append("• Net Study Time:     ").append(Durations.formatSeconds(studySecs));
        adjustedFrom.ifPresent(tracked -> recap.append(" *(you corrected it; the timer tracked ")
                .append(Durations.formatSeconds(tracked)).append(")*"));
        recap.append("\n\n");

        double averageBreak = clock.breaks() > 0 && breakCount > 0 ? clock.breaks() / breakCount : 0;
        recap.append("• Total Break Time:   ")
                .append(clock.breaks() > 0 ? Durations.formatSeconds(clock.breaks()) : "No Breaks Taken").append("\n")
                .append("• Average Break Time: ")
                .append(averageBreak > 0 ? Durations.formatSeconds(averageBreak) : "N/A").append("\n")
                .append("• Number of Breaks:   ").append(breakCount).append("\n");

        appendSubjects(recap, subjects);

        recap.append("\n\n• XP & RP Earned: ").append(String.format("%,d", points));
        // Say so only when the focus tiers actually reduced the reward (beyond the 5-minute block rounding).
        if (studySecs / 60 - rewardedMinutes >= 1)
            recap.append("\n• Long unbroken stretches earn less per hour; this session was rewarded as ")
                    .append(Durations.formatSeconds(rewardedMinutes * 60))
                    .append(" of study. A break of 15 minutes or more restores the full rate.");
        return recap.toString();
    }

    /**
     * One subject gets a single line; several get a list with each one's share of the session. Times are rounded to
     * the second, so a split of whole hours does not read as "59 minutes 59 seconds".
     */
    private static void appendSubjects(StringBuilder recap, List<SubjectTime> subjects) {
        if (subjects == null || subjects.isEmpty()) {
            recap.append("\n• No subjects studied this session.");
            return;
        }
        if (subjects.size() == 1) {
            SubjectTime only = subjects.getFirst();
            recap.append("\n• Subject Studied: **").append(only.code()).append("** for ")
                    .append(Durations.formatSeconds(Math.rint(only.seconds())));
            return;
        }
        double total = subjects.stream().mapToDouble(SubjectTime::seconds).sum();
        recap.append("\n• Subject Breakdown:");
        for (SubjectTime subject : subjects) {
            recap.append("\n   ◦ **").append(subject.code()).append("**: ").append(Durations.formatSeconds(Math.rint(subject.seconds())));
            if (total > 0) recap.append(" (").append(Math.round(subject.seconds() * 100 / total)).append("%)");
        }
    }
}
