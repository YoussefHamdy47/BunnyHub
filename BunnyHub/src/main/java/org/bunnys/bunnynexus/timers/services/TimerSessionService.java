package org.bunnys.bunnynexus.timers.services;

import com.mongodb.client.model.Filters;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.bunnys.bunnynexus.events.custom.AccountLevelUpEvent;
import org.bunnys.bunnynexus.events.custom.RecordBrokenEvent;
import org.bunnys.bunnynexus.events.custom.SemesterLevelUpEvent;
import org.bunnys.bunnynexus.timers.engine.LevelEngine;
import org.bunnys.database.models.timers.Session;
import org.bunnys.database.models.timers.Subject;
import org.bunnys.database.models.timers.TimerData;
import org.bunnys.database.models.user.BunnyUser;
import org.bunnys.handler.database.DB;
import org.bunnys.handler.utils.InteractionErrors.InputFailure;
import org.bunnys.handler.utils.InteractionErrors.StateFailure;
import org.bunnys.utils.Durations;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Study-session state changes: start, switch subject, pause, resume and stop. Each loads the timer document, applies
 * the change in memory and saves it with its optimistic revision, so a concurrent edit fails instead of being lost.
 */
public final class TimerSessionService {
    private TimerSessionService() {}

    private static final String TIMER_COLLECTION = "TimerData";
    private static final String USER_COLLECTION = "BunnyUsers";
    private static final String TIMER_ID_FIELD = "account.userID";
    private static final String USER_ID_FIELD = "userID";
    /**
     * A break at least this long ends the current study stretch, so the next one earns XP/RP at the full rate again
     * (see {@link LevelEngine#rewardedMinutes}). Shorter pauses do not, so a quick pause cannot reset the curve.
     */
    public static final double STRETCH_RESET_BREAK_SECS = 15 * 60;

    public static void startSession(String userId, String messageId, String channelId, String guildId, String topic) {
        TimerData timerData = getTimerDataOrThrow(userId);
        Session session = timerData.getSessionData();
        if (session.getSessionStartTime() != null)
            throw new StateFailure("You already have an active session running.");

        Subject subject = SubjectTopics.require(timerData.getCurrentSemester().getSemesterSubjects(), topic);
        long now = System.currentTimeMillis();
        timerData.getCurrentSemester().getSessionStartTimes().add(now);
        resetSessionFields(session, now, topic, messageId, channelId, guildId);
        session.getSubjectsStudied().add(subject.getSubjectCode());
        subject.setTimesStudied(subject.getTimesStudied() + 1);
        saveTimerData(userId, timerData);
    }

    public static void changeSubject(String userId, String newTopic) {
        TimerData timerData = getTimerDataOrThrow(userId);
        Subject next = SubjectTopics.require(timerData.getCurrentSemester().getSemesterSubjects(), newTopic);
        Session session = timerData.getSessionData();
        if (session.getSessionStartTime() == null)
            throw new StateFailure("You don't have an active session to modify.");
        if (session.getSessionBreaks().getSessionBreakStart() != null)
            throw new StateFailure("The timer is paused. Please unpause before changing subjects.");
        if (session.getSessionTopic() != null && SubjectTopics.code(session.getSessionTopic()).equals(SubjectTopics.code(newTopic)))
            throw new StateFailure("Your telemetry feed is already routed to this module.");

        var clock = SessionClock.of(session, System.currentTimeMillis());
        creditCurrentSubject(timerData, clock.activeStudy() - session.getSessionTime());
        session.setSessionTime(clock.activeStudy());
        session.setSessionTopic(newTopic);

        String code = SubjectTopics.code(newTopic);
        if (!session.getSubjectsStudied().contains(code)) {
            session.getSubjectsStudied().add(code);
            next.setTimesStudied(next.getTimesStudied() + 1);
        }
        saveTimerData(userId, timerData);
    }

    public static void pauseSession(String userId) {
        TimerData timerData = getTimerDataOrThrow(userId);
        Session session = timerData.getSessionData();
        requireActiveSession(session);
        if (session.getSessionBreaks().getSessionBreakStart() != null)
            throw new StateFailure("The timer is already paused.");

        session.getSessionBreaks().setSessionBreakStart(new Date(System.currentTimeMillis()));
        session.setNumberOfBreaks(session.getNumberOfBreaks() + 1);
        timerData.getCurrentSemester().setBreakCount(timerData.getCurrentSemester().getBreakCount() + 1);
        saveTimerData(userId, timerData);
    }

    /** @return how long the break that just ended lasted, in milliseconds */
    public static long unpauseSession(String userId) {
        TimerData timerData = getTimerDataOrThrow(userId);
        Session session = timerData.getSessionData();
        requireActiveSession(session);
        if (session.getSessionBreaks().getSessionBreakStart() == null)
            throw new StateFailure("The timer is not currently paused.");

        long now = System.currentTimeMillis();
        // With the break still open, active study is exactly what was studied before the pause.
        double studiedBeforeBreak = SessionClock.of(session, now).activeStudy();
        long breakMs = closeOpenBreak(session, now);
        if (breakMs >= STRETCH_RESET_BREAK_SECS * 1000) {
            double closed = session.getStudyStretches().stream().mapToDouble(Double::doubleValue).sum();
            session.getStudyStretches().add(Math.max(0, studiedBeforeBreak - closed));
        }
        saveTimerData(userId, timerData);
        return breakMs;
    }

    /**
     * Sessions at least this long are not ended until the user confirms the time or enters what they really studied:
     * a forgotten timer and an all-nighter look the same, so neither is capped silently.
     */
    public static final double CONFIRM_LONG_SESSION_SECS = 10 * 60 * 60;

    /**
     * A long session waiting for confirmation.
     *
     * @param sessionStart identifies the session, so a stale prompt can never end a later one
     * @param tracked      net study time the timer measured (elapsed minus breaks)
     * @param minimum      study time already logged to subjects at earlier switches; a correction cannot go below it
     */
    public record LongSession(long sessionStart, double tracked, double minimum) {}

    /**
     * How the user settled a long session: {@code reportedStudySecs} empty keeps the tracked time.
     * {@link #NONE} is for sessions that need no confirmation.
     */
    public record Confirmation(long sessionStart, OptionalDouble reportedStudySecs) {
        static final Confirmation NONE = new Confirmation(0, OptionalDouble.empty());
    }

    /** Empty when the running session can be ended straight away. */
    public static Optional<LongSession> longSession(String userId) {
        Session session = getTimerDataOrThrow(userId).getSessionData();
        if (session.getSessionStartTime() == null) return Optional.empty();
        var clock = SessionClock.of(session, System.currentTimeMillis());
        if (clock.activeStudy() < CONFIRM_LONG_SESSION_SECS) return Optional.empty();
        return Optional.of(new LongSession(session.getSessionStartTime().getTime(), clock.activeStudy(), session.getSessionTime()));
    }

    public static String stopSession(String userId, IReplyCallback interaction) {
        return stopSession(userId, interaction, false);
    }

    public static String stopSession(String userId, IReplyCallback interaction, boolean closeOpenBreak) {
        return stopSession(userId, interaction, closeOpenBreak, Confirmation.NONE);
    }

    /**
     * Ends the session, credits time and rewards, saves the account and timer atomically, then announces level-ups
     * and records (after the save, so listeners never see state that could still roll back).
     *
     * @param closeOpenBreak end a running break now instead of rejecting a paused session; used by the recovery
     *                       command when the session menu is unavailable.
     * @param confirmation   required for sessions of at least {@link #CONFIRM_LONG_SESSION_SECS}; may replace the
     *                       tracked study time with what the user reports, between the already-logged minimum and
     *                       the tracked time.
     */
    public static String stopSession(String userId, IReplyCallback interaction, boolean closeOpenBreak,
                                     Confirmation confirmation) {
        TimerData timerData = getTimerDataOrThrow(userId);
        Session session = timerData.getSessionData();
        requireActiveSession(session);
        long now = System.currentTimeMillis();
        boolean confirmed = confirmation != Confirmation.NONE;
        if (confirmed && confirmation.sessionStart() != session.getSessionStartTime().getTime())
            throw new StateFailure("That confirmation belongs to a session that already ended.");
        if (session.getSessionBreaks().getSessionBreakStart() != null) {
            if (!closeOpenBreak)
                throw new StateFailure("You cannot end the session while the timer is paused. Please unpause first.");
            closeOpenBreak(session, now);
        }

        BunnyUser userData = DB.findOne(BunnyUser.class, USER_COLLECTION, Filters.eq(USER_ID_FIELD, userId));
        if (userData == null)
            throw new StateFailure("User account not found. Please register first.");

        long startMs = session.getSessionStartTime().getTime();
        var clock = SessionClock.of(session, now);
        if (!confirmed && clock.activeStudy() >= CONFIRM_LONG_SESSION_SECS)
            throw new StateFailure("This session ran for " + Durations.formatSeconds(clock.activeStudy())
                    + ". Press **End Session** again to confirm how long you really studied.");
        double study = studyTime(clock, session.getSessionTime(), confirmation.reportedStudySecs());
        List<String> subjectsStudied = List.copyOf(session.getSubjectsStudied());
        int breakCount = session.getNumberOfBreaks();

        creditCurrentSubject(timerData, study - session.getSessionTime());
        // Time the user said they did not study is dropped, not counted as a break.
        SessionProgress.addToTotals(timerData, clock.breaks(), study);

        double previousLongest = timerData.getCurrentSemester().getLongestSession();
        if (previousLongest < study)
            timerData.getCurrentSemester().setLongestSession(study);
        // The first session of a semester sets the baseline; it is not announced as a record.
        boolean brokeRecord = previousLongest > 0 && previousLongest < study;

        // Long unbroken stretches earn less per hour instead of being capped; real breaks restore the full rate.
        double rewardedMinutes = LevelEngine.rewardedMinutes(SessionProgress.stretches(session.getStudyStretches(), study));
        long points = LevelEngine.calculateXP(rewardedMinutes);
        var rank = SessionProgress.applyRank(userData, points);
        var level = SessionProgress.applyLevel(timerData, points);
        SessionProgress.updateStreak(timerData, now);

        OptionalDouble adjustedFrom = confirmation.reportedStudySecs().isPresent() ? OptionalDouble.of(clock.activeStudy()) : OptionalDouble.empty();
        String recap = SessionProgress.recap(startMs, clock, study, adjustedFrom, breakCount, subjectsStudied, points, rewardedMinutes);
        clearSessionFields(session);
        TimerStore.saveProgress(userId, userData, timerData);

        var jda = interaction.getJDA();
        if (brokeRecord)
            jda.getEventManager().handle(new RecordBrokenEvent(jda, interaction,
                    RecordBrokenEvent.RecordType.SESSION, clock.activeStudy(), null));
        if (rank.hasRankedUp())
            jda.getEventManager().handle(new AccountLevelUpEvent(jda, interaction, rank.addedLevels(), rank.remainingRP(), userData));
        if (level.hasLeveledUp())
            jda.getEventManager().handle(new SemesterLevelUpEvent(jda, interaction, level.addedLevels(), level.remainingXP(), timerData));
        return recap;
    }

    /** Loads the caller's timer and requires an active semester. */
    public static TimerData getTimerDataOrThrow(String userId) {
        TimerData timerData = DB.findOne(TimerData.class, TIMER_COLLECTION, Filters.eq(TIMER_ID_FIELD, userId));
        if (timerData == null)
            throw new StateFailure("Timer account not found. Please register first.");
        if (timerData.getCurrentSemester() == null || timerData.getCurrentSemester().getSemesterName() == null)
            throw new StateFailure("No active semester found. Start a semester first.");
        return timerData;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The study time a session is credited with. A reported time must lie between what earlier subject switches
     * already logged (that time is committed to subjects) and what the timer tracked (nobody studies more than the
     * timer ran). Entries are in whole minutes, so a minute of slack is allowed at both ends and then clamped.
     */
    static double studyTime(SessionClock clock, double alreadyLogged, OptionalDouble reported) {
        if (reported.isEmpty()) return clock.activeStudy();
        double value = reported.getAsDouble();
        if (Double.isNaN(value) || value < 0)
            throw new InputFailure("Enter how long you studied, in hours and minutes.");
        if (value >= clock.activeStudy() + 60)
            throw new InputFailure("You can't have studied longer than the timer ran (" + Durations.formatSeconds(clock.activeStudy()) + ").");
        if (value <= alreadyLogged - 60)
            throw new InputFailure("At least " + Durations.formatSeconds(alreadyLogged)
                    + " was already logged when you switched subjects; enter that much or more.");
        return Math.max(alreadyLogged, Math.min(value, clock.activeStudy()));
    }

    private static void requireActiveSession(Session session) {
        if (session.getSessionStartTime() == null)
            throw new StateFailure("You don't have an active session.");
    }

    /** Folds the running break into the stored total; a backward clock jump counts as zero, never negative. */
    private static long closeOpenBreak(Session session, long nowMillis) {
        var breaks = session.getSessionBreaks();
        long breakMs = Math.max(0, nowMillis - breaks.getSessionBreakStart().getTime());
        breaks.setSessionBreakTime(breaks.getSessionBreakTime() + breakMs / 1000.0);
        breaks.setSessionBreakStart(null);
        return breakMs;
    }

    /** Credits study time not yet allocated to the subject currently being studied. */
    private static void creditCurrentSubject(TimerData timerData, double seconds) {
        String topic = timerData.getSessionData().getSessionTopic();
        if (topic == null || topic.isBlank()) return;
        double credit = Math.max(0, seconds);
        findSubject(timerData, SubjectTopics.code(topic)).ifPresent(subject -> subject.setTotalStudyTime(
                (subject.getTotalStudyTime() == null ? 0.0 : subject.getTotalStudyTime()) + credit));
    }

    private static Optional<Subject> findSubject(TimerData timerData, String code) {
        return timerData.getCurrentSemester().getSemesterSubjects().stream()
                .filter(s -> s.getSubjectCode().equalsIgnoreCase(code)).findFirst();
    }

    private static void resetSessionFields(Session session, long now, String topic, String messageId, String channelId, String guildId) {
        session.setSessionStartTime(new Date(now));
        session.setLastSessionDate(new Date(now));
        session.setSessionTopic(topic);
        session.setMessageID(messageId);
        session.setChannelID(channelId);
        session.setGuildID(guildId);
        session.setNumberOfBreaks(0);
        session.setSessionTime(0.0);
        session.getSessionBreaks().setSessionBreakStart(null);
        session.getSessionBreaks().setSessionBreakTime(0.0);
        session.getSubjectsStudied().clear();
        session.getStudyStretches().clear();
    }

    private static void clearSessionFields(Session session) {
        session.setLastSessionTopic(session.getSessionTopic());
        session.setSessionStartTime(null);
        session.setChannelID(null);
        session.setMessageID(null);
        session.setGuildID(null);
        session.setSessionTopic(null);
        session.setNumberOfBreaks(0);
        session.setSessionTime(0.0);
        session.getSessionBreaks().setSessionBreakTime(0.0);
        session.getSessionBreaks().setSessionBreakStart(null);
        session.getSubjectsStudied().clear();
        session.getStudyStretches().clear();
    }

    private static void saveTimerData(String userId, TimerData data) {
        DB.save(TimerData.class, TIMER_COLLECTION, Filters.eq(TIMER_ID_FIELD, userId), data);
    }
}
