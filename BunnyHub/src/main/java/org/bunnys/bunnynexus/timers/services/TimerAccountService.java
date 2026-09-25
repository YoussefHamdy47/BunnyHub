package org.bunnys.bunnynexus.timers.services;

import org.bunnys.handler.utils.InteractionErrors.InputFailure;
import org.bunnys.handler.utils.InteractionErrors.StateFailure;

import com.mongodb.client.model.Filters;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.bunnys.database.models.timers.Account;
import org.bunnys.database.models.timers.Semester;
import org.bunnys.database.models.timers.TimerData;
import org.bunnys.database.models.user.BunnyUser;
import org.bunnys.handler.database.DB;
import org.bunnys.bunnynexus.events.custom.AccountLevelUpEvent;
import org.bunnys.bunnynexus.events.custom.RecordBrokenEvent;
import org.bunnys.bunnynexus.timers.engine.LevelEngine;
import org.bunnys.utils.Utils;

public class TimerAccountService {

    public static void registerAccount(String userId) {
        DB.ensureAccount(userId);
    }

    public static void registerSemester(String userId, String semesterName) {
        if (semesterName == null || semesterName.trim().isEmpty() || semesterName.length() > 80)
            throw new InputFailure("Semester name must contain 1 to 80 characters.");

        TimerData timerData = DB.findOne(TimerData.class, "TimerData", Filters.eq("account.userID", userId));
        if (timerData == null)
            throw new StateFailure("No timer account found. Please register first.");

        if (timerData.getCurrentSemester() != null && timerData.getCurrentSemester().getSemesterName() != null) {
            throw new StateFailure("Semester '" + timerData.getCurrentSemester().getSemesterName()
                    + "' is already active. End it first.");
        }

        Semester newSemester = new Semester();
        newSemester.setSemesterName(semesterName);
        timerData.setCurrentSemester(newSemester);

        DB.save(TimerData.class, "TimerData", Filters.eq("account.userID", userId), timerData);
    }

    public static String endSemester(String userId, IReplyCallback interaction, Long confirmedRevision) {
        TimerData timerData = DB.findOne(TimerData.class, "TimerData", Filters.eq("account.userID", userId));
        BunnyUser userData = DB.findOne(BunnyUser.class, "BunnyUsers", Filters.eq("userID", userId));

        if (timerData == null || timerData.getCurrentSemester() == null
                || timerData.getCurrentSemester().getSemesterName() == null)
            throw new StateFailure("No active semester found to end.");

        if (!java.util.Objects.equals(confirmedRevision, timerData.getRevision()))
            throw new StateFailure("Your semester changed during confirmation. Please try again.");

        if (userData == null)
            throw new StateFailure("User account not found. Please register first.");
        if (timerData.getSessionData().getSessionStartTime() != null)
            throw new StateFailure("End your active study session before archiving the semester.");
        Semester currentSemester = timerData.getCurrentSemester();

        // Check for longest semester record
        Semester longestSemester = timerData.getAccount().getLongestSemester();
        boolean longer = longestSemester == null || currentSemester.getSemesterTime() > longestSemester.getSemesterTime();
        if (longer) timerData.getAccount().setLongestSemester(currentSemester);
        // The first archived semester sets the baseline; only beating an earlier one is announced.
        boolean brokeRecord = longer && longestSemester != null;
        long convertedXP = LevelEngine.convertSeasonLevel(currentSemester.getSemesterLevel())
                + (long) currentSemester.getSemesterXP();

        String totalTimeStr = currentSemester.getSemesterTime() > 0
                ? Utils.msToTime((long) (currentSemester.getSemesterTime() * 1000)).orElse("0s")
                : "0s";
        String longestSessionStr = currentSemester.getLongestSession() > 0
                ? Utils.msToTime((long) (currentSemester.getLongestSession() * 1000)).orElse("0s")
                : "0s";
        String totalBreakTimeStr = currentSemester.getTotalBreakTime() > 0
                ? Utils.msToTime((long) (currentSemester.getTotalBreakTime() * 1000)).orElse("0s")
                : "0s";

        long semesterXP = (long) currentSemester.getSemesterXP();
        long totalSemesterXP = LevelEngine.calculateTotalSeasonXP(currentSemester.getSemesterLevel()) + semesterXP;

        StringBuilder recap = new StringBuilder();
        recap.append("**• Total Time:** ").append(totalTimeStr).append("\n")
                .append("**• Number of Sessions:** ").append(currentSemester.getSessionStartTimes().size()).append("\n")
                .append("**• Total Break Time:** ").append(totalBreakTimeStr).append("\n")
                .append("**• Longest Session:** ").append(longestSessionStr).append("\n")
                .append("**• Semester Level:** ").append(currentSemester.getSemesterLevel()).append("\n")
                .append("**• Semester XP:** ").append(String.format("%,d", totalSemesterXP)).append("\n")
                .append("**• Account XP Converted:** ").append(String.format("%,d", convertedXP));
        int sessionCount = currentSemester.getSessionStartTimes().size();
        if (sessionCount > 0)
            recap.append("\n**• Average Session:** ").append(Utils.msToTime(
                    (long) (currentSemester.getSemesterTime() * 1000 / sessionCount)).orElse("0s"));
        recap.append("\n**• Subjects Tracked:** ").append(currentSemester.getSemesterSubjects().size());
        currentSemester.getSemesterSubjects().stream()
                .filter(subject -> subject.getTotalStudyTime() != null && subject.getTotalStudyTime() > 0)
                .max(java.util.Comparator.comparingDouble(org.bunnys.database.models.timers.Subject::getTotalStudyTime))
                .ifPresent(subject -> recap.append("\n**• Top Subject:** ").append(subject.getSubjectCode())
                        .append(" — ").append(Utils.msToTime((long) (subject.getTotalStudyTime() * 1000)).orElse("0s")));
        LevelEngine.RankResult rankCheck = LevelEngine.checkRank(userData.getRank(), userData.getRp(), convertedXP);
        if (rankCheck.hasRankedUp()) {
            userData.setRank(userData.getRank() + rankCheck.addedLevels());
            userData.setRp(rankCheck.remainingRP());
            recap.append("\n🎉 **RANK UP!** You are now Rank ").append(userData.getRank());

        } else
            userData.setRp(Math.addExact(userData.getRp(), convertedXP));

        timerData.setCurrentSemester(new Semester());

        DB.saveProgress(userId, userData, timerData, currentSemester);
        PendingSessionManager.cancelPendingSession(userId);
        if (brokeRecord)
            interaction.getJDA().getEventManager().handle(new RecordBrokenEvent(
                    interaction.getJDA(), interaction, RecordBrokenEvent.RecordType.SEMESTER, null, currentSemester));
        if (rankCheck.hasRankedUp())
            interaction.getJDA().getEventManager().handle(new AccountLevelUpEvent(
                    interaction.getJDA(), interaction, rankCheck.addedLevels(), rankCheck.remainingRP(), userData));

        return recap.toString();
    }
}
