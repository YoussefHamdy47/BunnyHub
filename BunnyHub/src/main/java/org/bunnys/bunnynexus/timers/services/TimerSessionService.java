package org.bunnys.bunnynexus.timers.services;

import org.bunnys.handler.utils.InteractionErrors.StateFailure;

import com.mongodb.client.model.Filters;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.bunnys.database.models.timers.Session;
import org.bunnys.database.models.timers.Subject;
import org.bunnys.database.models.timers.TimerData;
import org.bunnys.database.models.user.BunnyUser;
import org.bunnys.handler.database.DB;
import org.bunnys.bunnynexus.events.custom.AccountLevelUpEvent;
import org.bunnys.bunnynexus.events.custom.RecordBrokenEvent;
import org.bunnys.bunnynexus.events.custom.SemesterLevelUpEvent;
import org.bunnys.bunnynexus.timers.engine.LevelEngine;
import org.bunnys.utils.Utils;

import java.util.*;

@SuppressWarnings("unused")
public class TimerSessionService {

    private static final String TIMER_COLLECTION = "TimerData";
    private static final String USER_COLLECTION = "BunnyUsers";
    private static final String TIMER_ID_FIELD = "account.userID";
    private static final String USER_ID_FIELD = "userID";
    /** Longest study time a single session can convert into XP/RP. */
    public static final double MAX_REWARDED_SESSION_SECS = 12 * 60 * 60;

    public static void startSession(String userId, String messageId, String channelId,
            String guildId, String topic) {
        TimerData timerData = getTimerDataOrThrow(userId);
        Session session = timerData.getSessionData();

        if (session.getSessionStartTime() != null)
            throw new StateFailure("You already have an active session running.");

        SubjectTopics.require(timerData.getCurrentSemester().getSemesterSubjects(), topic);
        long now = System.currentTimeMillis();
        timerData.getCurrentSemester().getSessionStartTimes().add(now);
        resetSessionFields(session, now, topic, messageId, channelId, guildId);

        if (hasTopic(topic)) {
            String code = extractSubjectCode(topic);
            findSubject(timerData, code).ifPresent(subject -> {
                safeListAdd(session.getSubjectsStudied(), subject.getSubjectCode());
                subject.setTimesStudied(subject.getTimesStudied() + 1);
            });
        }

        saveTimerData(userId, timerData);
    }

    public static void changeSubject(String userId, String newTopic) {
        TimerData timerData = getTimerDataOrThrow(userId);
        org.bunnys.bunnynexus.timers.services.SubjectTopics.require(timerData.getCurrentSemester().getSemesterSubjects(), newTopic);
        org.bunnys.database.models.timers.Session session = timerData.getSessionData();

        if (session.getSessionStartTime() == null)
            throw new StateFailure("You don't have an active session to modify.");

        if (session.getSessionBreaks() != null && session.getSessionBreaks().getSessionBreakStart() != null)
            throw new StateFailure("The timer is paused. Please unpause before changing subjects.");

        if (session.getSessionTopic() != null
                && SubjectTopics.code(session.getSessionTopic()).equals(SubjectTopics.code(newTopic)))
            throw new StateFailure("Your telemetry feed is already routed to this module.");

        java.time.Instant now = java.time.Instant.now();
        long startMs = session.getSessionStartTime().getTime();
        double totalBreakSecs = session.getSessionBreaks() != null ? session.getSessionBreaks().getSessionBreakTime()
                : 0.0;
        double totalElapsedSecs = (now.toEpochMilli() - startMs) / 1000.0;
        double totalActiveSecs = activeStudySeconds(session, totalElapsedSecs, totalBreakSecs);

        double previouslyAllocatedSecs = session.getSessionTime();
        double timeToCredit = Math.max(0, totalActiveSecs - previouslyAllocatedSecs);

        String oldTopic = session.getSessionTopic();
        if (oldTopic != null && !oldTopic.trim().isEmpty()) {
            String oldCode = org.bunnys.bunnynexus.timers.services.SubjectTopics.code(oldTopic);
            if (timerData.getCurrentSemester().getSemesterSubjects() != null) {
                timerData.getCurrentSemester().getSemesterSubjects().stream()
                        .filter(s -> s.getSubjectCode().equalsIgnoreCase(oldCode))
                        .findFirst()
                        .ifPresent(subject -> {
                            double currentSubTime = subject.getTotalStudyTime() != null ? subject.getTotalStudyTime()
                                    : 0.0;
                            subject.setTotalStudyTime(currentSubTime + timeToCredit);
                        });
            }
        }

        session.setSessionTime(totalActiveSecs);
        session.setSessionTopic(newTopic);

        if (newTopic != null && !newTopic.trim().isEmpty()) {
            String newCode = org.bunnys.bunnynexus.timers.services.SubjectTopics.code(newTopic);

            if (!session.getSubjectsStudied().contains(newCode)) {
                session.getSubjectsStudied().add(newCode);

                if (timerData.getCurrentSemester().getSemesterSubjects() != null) {
                    timerData.getCurrentSemester().getSemesterSubjects().stream()
                            .filter(s -> s.getSubjectCode().equalsIgnoreCase(newCode))
                            .findFirst()
                            .ifPresent(subject -> subject.setTimesStudied(subject.getTimesStudied() + 1));
                }
            }
        }

        DB.save(TimerData.class, "TimerData", Filters.eq("account.userID", userId), timerData);

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

    public static long unpauseSession(String userId) {
        TimerData timerData = getTimerDataOrThrow(userId);
        Session session = timerData.getSessionData();

        requireActiveSession(session);
        if (session.getSessionBreaks().getSessionBreakStart() == null)
            throw new StateFailure("The timer is not currently paused.");

        long breakStartMs = session.getSessionBreaks().getSessionBreakStart().getTime();
        long elapsedBreakMs = Math.max(0, System.currentTimeMillis() - breakStartMs);

        session.getSessionBreaks().setSessionBreakTime(
                session.getSessionBreaks().getSessionBreakTime() + (elapsedBreakMs / 1000.0));
        session.getSessionBreaks().setSessionBreakStart(null);

        saveTimerData(userId, timerData);
        return elapsedBreakMs;
    }

    public static net.dv8tion.jda.api.entities.MessageEmbed getTelemetryEmbed(String userId) {
        TimerData timerData = getTimerDataOrThrow(userId);
        Session session = timerData.getSessionData();

        net.dv8tion.jda.api.EmbedBuilder eb = new net.dv8tion.jda.api.EmbedBuilder();
        eb.setColor(org.bunnys.utils.AppDesign.ColorCodes.CYAN);

        if (session.getSessionStartTime() == null) {
            eb.setTitle("💎 Live Telemetry");
            eb.setDescription("> *No active telemetry feed.*");
            return eb.build();
        }

        String topic = session.getSessionTopic();
        String cleanTopic = topic != null ? org.bunnys.bunnynexus.timers.services.SubjectTopics.code(topic) : "UNKNOWN";
        eb.setTitle("💎 Live Telemetry | " + cleanTopic);

        long now = System.currentTimeMillis();
        long startMs = session.getSessionStartTime().getTime();

        double storedBreakSecs = session.getSessionBreaks().getSessionBreakTime();
        double activeBreakSecs = (session.getSessionBreaks().getSessionBreakStart() != null)
                ? Math.max(0, now - session.getSessionBreaks().getSessionBreakStart().getTime()) / 1000.0
                : 0.0;

        double totalBreakSecs = storedBreakSecs + activeBreakSecs;
        double totalElapsedSecs = (now - startMs) / 1000.0;
        double activeStudySecs = activeStudySeconds(session, totalElapsedSecs, totalBreakSecs);

        StringBuilder info = new StringBuilder();
        info.append("✦ **Start Time:** <t:").append(startMs / 1000).append(":f>\n");
        info.append("✦ **Current Uptime:** <t:").append(startMs / 1000).append(":R>\n\n");

        info.append("✦ **Net Study Time:** `").append(formatSecs(activeStudySecs)).append("`\n");
        info.append("✦ **Total Break Time:** `").append(formatSecs(totalBreakSecs)).append("`\n");
        info.append("✦ **Break Count:** `").append(session.getNumberOfBreaks()).append("`\n");

        if (session.getSessionBreaks().getSessionBreakStart() != null)
            info.append("\n> ⏸ *Currently on a break. Duration: ").append(formatSecs(activeBreakSecs)).append(".*");
        else
            info.append("\n> ▶ *Telemetry feed active and recording.*");

        eb.setDescription(info.toString());
        eb.setTimestamp(java.time.Instant.now());
        return eb.build();
    }

    /**
     * Stops the current session, persists all stat updates, fires events, and
     * returns a formatted recap string.
     *
     * <p>
     * Key ordering:
     * <ol>
     * <li>Validate session state (before fetching BunnyUser to avoid wasted DB
     * calls).</li>
     * <li>Fetch BunnyUser with a null guard.</li>
     * <li>Compute all derived values.</li>
     * <li>Mutate both documents in memory.</li>
     * <li>Persist both documents.</li>
     * <li>Fire events (after persistence so handlers see fresh DB state).</li>
     * </ol>
     */
    public static String stopSession(String userId, IReplyCallback interaction) {
        return stopSession(userId, interaction, false);
    }

    /**
     * @param closeOpenBreak end a running break now instead of rejecting a paused session; used by
     *                       the recovery command when the session menu is unavailable.
     */
    public static String stopSession(String userId, IReplyCallback interaction, boolean closeOpenBreak) {
        TimerData timerData = getTimerDataOrThrow(userId);
        Session session = timerData.getSessionData();

        requireActiveSession(session);
        if (session.getSessionBreaks().getSessionBreakStart() != null) {
            if (!closeOpenBreak)
                throw new StateFailure(
                        "You cannot end the session while the timer is paused. Please unpause first.");
            long openBreakMs = System.currentTimeMillis() - session.getSessionBreaks().getSessionBreakStart().getTime();
            session.getSessionBreaks().setSessionBreakTime(
                    session.getSessionBreaks().getSessionBreakTime() + Math.max(0, openBreakMs) / 1000.0);
            session.getSessionBreaks().setSessionBreakStart(null);
        }

        BunnyUser userData = DB.findOne(BunnyUser.class, USER_COLLECTION,
                Filters.eq(USER_ID_FIELD, userId));
        if (userData == null)
            throw new StateFailure("User account not found. Please register first.");

        long now = System.currentTimeMillis();
        long startMs = session.getSessionStartTime().getTime();
        double totalBreakSecs = session.getSessionBreaks().getSessionBreakTime();
        double totalElapsedSecs = (now - startMs) / 1000.0;
        double activeStudySecs = activeStudySeconds(session, totalElapsedSecs, totalBreakSecs);

        List<String> subjectsStudied = session.getSubjectsStudied();
        int numberOfBreaks = session.getNumberOfBreaks();

        double previouslyAllocatedSecs = session.getSessionTime();
        double finalSubjectTime = Math.max(0, activeStudySecs - previouslyAllocatedSecs);

        String currentTopic = session.getSessionTopic();
        if (currentTopic != null && !currentTopic.trim().isEmpty()) {
            String code = org.bunnys.bunnynexus.timers.services.SubjectTopics.code(currentTopic);
            timerData.getCurrentSemester().getSemesterSubjects().stream()
                    .filter(s -> s.getSubjectCode().equalsIgnoreCase(code))
                    .findFirst()
                    .ifPresent(subject -> {
                        double current = subject.getTotalStudyTime() != null ? subject.getTotalStudyTime() : 0.0;
                        subject.setTotalStudyTime(current + finalSubjectTime);
                    });
        }

        updateSemesterStats(timerData, totalBreakSecs, activeStudySecs);

        double previousLongest = timerData.getCurrentSemester().getLongestSession();
        if (previousLongest < activeStudySecs)
            timerData.getCurrentSemester().setLongestSession(activeStudySecs);
        // The first session of a semester sets the baseline; it is not announced as a record.
        boolean brokeRecord = previousLongest > 0 && previousLongest < activeStudySecs;

        // Forgotten timers keep their recorded time but cannot mint unbounded progression.
        boolean rewardCapped = activeStudySecs > MAX_REWARDED_SESSION_SECS;
        long pointsEarned = LevelEngine.calculateXP(Math.min(activeStudySecs, MAX_REWARDED_SESSION_SECS) / 60.0);

        LevelEngine.RankResult rankResult = applyRankProgress(userData, pointsEarned);
        LevelEngine.LevelResult levelResult = applyLevelProgress(timerData, pointsEarned);
        updateStreak(timerData, now);

        String recap = buildRecap(startMs, totalElapsedSecs, activeStudySecs,
                totalBreakSecs, numberOfBreaks, subjectsStudied, pointsEarned, rewardCapped);

        clearSessionFields(session);

        DB.saveProgress(userId, userData, timerData);

        if (brokeRecord)
            interaction.getJDA().getEventManager().handle(new RecordBrokenEvent(
                    interaction.getJDA(), interaction,
                    RecordBrokenEvent.RecordType.SESSION, activeStudySecs, null));

        if (rankResult.hasRankedUp())
            interaction.getJDA().getEventManager().handle(new AccountLevelUpEvent(
                    interaction.getJDA(), interaction,
                    rankResult.addedLevels(), rankResult.remainingRP(), userData));

        if (levelResult.hasLeveledUp())
            interaction.getJDA().getEventManager().handle(new SemesterLevelUpEvent(
                    interaction.getJDA(), interaction,
                    levelResult.addedLevels(), levelResult.remainingXP(), timerData));

        return recap;
    }

    /** Preserve study time already credited before a backward wall-clock adjustment. */
    private static double activeStudySeconds(Session session, double elapsedSeconds, double breakSeconds) {
        // A backward wall-clock adjustment must not erase the allocation watermark. Otherwise
        // later subject switches can credit the same seconds again when the clock catches up.
        return Math.max(session.getSessionTime(), Math.max(0, elapsedSeconds - breakSeconds));
    }

    /** Throws if no session is currently active. */
    private static void requireActiveSession(Session session) {
        if (session.getSessionStartTime() == null)
            throw new StateFailure("You don't have an active session.");
    }

    /**
     * Initializes all session fields for a fresh start.
     */
    private static void resetSessionFields(Session session, long now, String topic,
            String messageId, String channelId, String guildId) {
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
        safeListClear(session.getSubjectsStudied());
    }

    /**
     * Wipes all transient session state after a session ends.
     */
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
        safeListClear(session.getSubjectsStudied());
    }

    /**
     * Adds this session's time to semester and lifetime accumulators.
     */
    private static void updateSemesterStats(TimerData timerData,
            double totalBreakSecs,
            double activeStudySecs) {
        var semester = timerData.getCurrentSemester();
        semester.setTotalBreakTime(semester.getTotalBreakTime() + totalBreakSecs);
        semester.setSemesterTime(semester.getSemesterTime() + activeStudySecs);
        timerData.getAccount().setLifetimeTime(timerData.getAccount().getLifetimeTime() + activeStudySecs);
    }

    /**
     * Applies earned points to the account RP track and returns the result.
     */
    private static LevelEngine.RankResult applyRankProgress(BunnyUser userData, long pointsEarned) {
        long currentRP = userData.getRp();
        LevelEngine.RankResult result = LevelEngine.checkRank(userData.getRank(), currentRP, pointsEarned);

        if (result.hasRankedUp()) {
            userData.setRank(userData.getRank() + Math.max(1, result.addedLevels()));
            userData.setRp(result.remainingRP());
        } else {
            userData.setRp(Math.addExact(currentRP, pointsEarned));
        }
        return result;
    }

    /**
     * Applies earned points to the semester XP track and returns the result.
     */
    private static LevelEngine.LevelResult applyLevelProgress(TimerData timerData, long pointsEarned) {
        var semester = timerData.getCurrentSemester();
        long currentXP = (long) semester.getSemesterXP();
        LevelEngine.LevelResult result = LevelEngine.checkLevel(
                semester.getSemesterLevel(), currentXP, pointsEarned);

        if (result.hasLeveledUp()) {
            semester.setSemesterLevel(semester.getSemesterLevel() + Math.max(1, result.addedLevels()));
            semester.setSemesterXP(result.remainingXP());
        } else {
            semester.setSemesterXP(currentXP + pointsEarned);
        }
        return result;
    }

    /** Streaks count calendar days in UTC, independent of server timezone and session hour. */
    static void updateStreak(TimerData timerData, long now) {
        var semester = timerData.getCurrentSemester();
        var today = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC).toLocalDate();
        Date lastUpdate = semester.getLastStreakUpdate();
        long days = lastUpdate == null ? Long.MAX_VALUE : java.time.temporal.ChronoUnit.DAYS.between(
                lastUpdate.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate(), today);
        if (days > 1) semester.setStreak(1);
        else if (days == 1) semester.setStreak(semester.getStreak() + 1);
        if (days > 0) semester.setLastStreakUpdate(new Date(now));
        semester.setLongestStreak(Math.max(semester.getLongestStreak(), semester.getStreak()));
    }
    /**
     * Builds the session end recap string.
     */
    private static String buildRecap(long startMs, double totalElapsedSecs,
            double activeStudySecs, double totalBreakSecs,
            int numberOfBreaks, List<String> subjectsStudied,
            long pointsEarned, boolean rewardCapped) {
        StringBuilder recap = new StringBuilder();

        recap.append("• Start Time: <t:").append(startMs / 1000).append(":F>\n")
                .append("• Total Time Elapsed: ").append(formatSecs(totalElapsedSecs)).append("\n")
                .append("• Net Study Time:     ").append(formatSecs(activeStudySecs)).append("\n\n");

        double avgBreakSecs = (totalBreakSecs > 0 && numberOfBreaks > 0)
                ? totalBreakSecs / numberOfBreaks
                : 0.0;

        recap.append("• Total Break Time:   ")
                .append(totalBreakSecs > 0 ? formatSecs(totalBreakSecs) : "No Breaks Taken").append("\n")
                .append("• Average Break Time: ")
                .append(avgBreakSecs > 0 ? formatSecs(avgBreakSecs) : "N/A").append("\n")
                .append("• Number of Breaks:   ").append(numberOfBreaks).append("\n");

        if (subjectsStudied != null && !subjectsStudied.isEmpty())
            recap.append("\n• Studied Subjects: ").append(String.join(", ", subjectsStudied));
        else
            recap.append("\n• No subjects studied this session.");

        recap.append("\n\n• XP & RP Earned: ").append(String.format("%,d", pointsEarned));
        if (rewardCapped)
            recap.append("\n• XP is capped at ").append(formatSecs(MAX_REWARDED_SESSION_SECS)).append(" per session.");

        return recap.toString();
    }

    private static boolean hasTopic(String topic) {
        return topic != null && !topic.trim().isEmpty();
    }

    private static String extractSubjectCode(String topic) {
        return SubjectTopics.code(topic);
    }

    private static Optional<Subject> findSubject(TimerData timerData, String subjectCode) {
        if (subjectCode.isEmpty())
            return Optional.empty();
        return timerData.getCurrentSemester().getSemesterSubjects().stream()
                .filter(s -> s.getSubjectCode().equalsIgnoreCase(subjectCode))
                .findFirst();
    }

    /** Null-safe list add. */
    private static void safeListAdd(List<String> list, String value) {
        if (list != null)
            list.add(value);
    }

    /** Null-safe list clear. */
    private static void safeListClear(List<String> list) {
        if (list != null)
            list.clear();
    }

    /** Formats a duration in seconds to a human-readable string. */
    private static String formatSecs(double seconds) {
        return Utils.msToTime((long) (seconds * 1000)).orElse("0s");
    }

    /**
     * Loads and validates TimerData for a given user.
     */
    public static TimerData getTimerDataOrThrow(String userId) {
        TimerData timerData = DB.findOne(TimerData.class, TIMER_COLLECTION,
                Filters.eq(TIMER_ID_FIELD, userId));
        if (timerData == null)
            throw new StateFailure("Timer account not found. Please register first.");
        if (timerData.getCurrentSemester() == null
                || timerData.getCurrentSemester().getSemesterName() == null)
            throw new StateFailure("No active semester found. Start a semester first.");
        return timerData;
    }

    private static void saveTimerData(String userId, TimerData data) {
        DB.save(TimerData.class, TIMER_COLLECTION, Filters.eq(TIMER_ID_FIELD, userId), data);
    }
}
