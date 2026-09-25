package org.bunnys.bunnynexus.timers.services;

import org.bunnys.handler.utils.InteractionErrors.InputFailure;
import org.bunnys.handler.utils.InteractionErrors.StateFailure;

import com.mongodb.client.model.Filters;
import org.bunnys.database.models.timers.Subject;
import org.bunnys.database.models.timers.TimerData;
import org.bunnys.database.models.user.BunnyUser;
import org.bunnys.handler.database.DB;

public class TimerSubjectService {

    public record SubjectUpdate(String code, String name, Integer credits, String grade,
                                Integer marksLost, boolean clearGrade) {}

    public static java.util.List<String> subjectCodes(String userId, boolean account) {
        java.util.List<Subject> subjects;
        if (account) {
            var user = DB.findOne(BunnyUser.class, "BunnyUsers", Filters.eq("userID", userId));
            subjects = user == null ? java.util.List.of() : user.getSubjects();
        } else {
            var timer = DB.findOne(TimerData.class, "TimerData", Filters.eq("account.userID", userId));
            subjects = timer == null ? java.util.List.of() : timer.getCurrentSemester().getSemesterSubjects();
        }
        return subjects.stream().map(Subject::getSubjectCode).filter(java.util.Objects::nonNull)
                .map(String::trim).filter(code -> !code.isEmpty())
                .map(code -> code.toUpperCase(java.util.Locale.ROOT)).distinct().sorted().toList();
    }

    public static Subject updateSubject(String userId, boolean account, String code, SubjectUpdate update) {
        java.util.Objects.requireNonNull(update);
        if (update.code() == null && update.name() == null && update.credits() == null
                && update.grade() == null && update.marksLost() == null && !update.clearGrade())
            throw new InputFailure("Provide at least one course detail to update.");
        if (update.clearGrade() && update.grade() != null)
            throw new InputFailure("Choose either a new grade or clear-grade, not both.");
        if (update.marksLost() != null && update.marksLost() < 0)
            throw new InputFailure("Marks lost cannot be negative.");

        BunnyUser user = account ? DB.findOne(BunnyUser.class, "BunnyUsers", Filters.eq("userID", userId)) : null;
        TimerData timer = account ? null : TimerSessionService.getTimerDataOrThrow(userId);
        if (account && user == null) throw new StateFailure("User account not found.");
        var subjects = account ? user.getSubjects() : timer.getCurrentSemester().getSemesterSubjects();
        Subject existing = subjects.stream().filter(subject -> matches(subject, code)).findFirst()
                .orElseThrow(() -> new InputFailure("Course not found in the selected destination."));

        // Validate a candidate first; retain study history and avoid partial edits on invalid input.
        Subject candidate = new Subject();
        candidate.setSubjectCode(update.code() == null ? existing.getSubjectCode() : update.code());
        candidate.setSubjectName(update.name() == null ? existing.getSubjectName() : update.name());
        candidate.setCreditHours(update.credits() == null ? existing.getCreditHours() : update.credits());
        candidate.setGrade(update.clearGrade() ? null : update.grade() == null ? existing.getGrade() : update.grade());
        validate(candidate);
        if (subjects.stream().anyMatch(subject -> subject != existing && matches(subject, candidate.getSubjectCode())))
            throw new InputFailure("Another course already uses that code.");
        boolean renamed = !matches(existing, candidate.getSubjectCode());
        if (!account && renamed && timer.getSessionData().getSessionStartTime() != null)
            throw new StateFailure("End your study session before changing a course code.");
        String oldCode = existing.getSubjectCode();
        existing.setSubjectCode(candidate.getSubjectCode());
        existing.setSubjectName(candidate.getSubjectName());
        existing.setCreditHours(candidate.getCreditHours());
        existing.setGrade(candidate.getGrade());
        if (update.marksLost() != null) existing.setMarksLost(update.marksLost());
        if (account) DB.save(BunnyUser.class, "BunnyUsers", Filters.eq("userID", userId), user);
        else {
            if (renamed) timer.getSessionData().getSubjectsStudied().replaceAll(
                    studied -> studied.equalsIgnoreCase(oldCode) ? existing.getSubjectCode() : studied);
            DB.save(TimerData.class, "TimerData", Filters.eq("account.userID", userId), timer);
        }
        return existing;
    }

    public static boolean matches(Subject subject, String selection) {
        return subject.getSubjectCode() != null
                && subject.getSubjectCode().trim().equalsIgnoreCase(SubjectTopics.code(selection));
    }

    public static void addSubjectToAccount(String userId, Subject subject) {
        validate(subject);
        BunnyUser user = DB.findOne(BunnyUser.class, "BunnyUsers", Filters.eq("userID", userId));
        if (user == null)
            throw new StateFailure("User account not found.");

        boolean exists = user.getSubjects().stream()
                .anyMatch(s -> s.getSubjectCode().equalsIgnoreCase(subject.getSubjectCode()));

        if (exists)
            throw new InputFailure(
                    "Subject '" + subject.getSubjectCode() + "' is already registered in your account.");

        user.getSubjects().add(subject);
        DB.save(BunnyUser.class, "BunnyUsers", Filters.eq("userID", userId), user);
    }

    public static void removeSubjectFromAccount(String userId, String subjectCode) {
        BunnyUser user = DB.findOne(BunnyUser.class, "BunnyUsers", Filters.eq("userID", userId));
        if (user == null)
            throw new StateFailure("User account not found.");

        boolean removed = user.getSubjects().removeIf(s -> matches(s, subjectCode));

        if (!removed)
            throw new InputFailure("You haven't registered '" + subjectCode + "' in your account.");

        DB.save(BunnyUser.class, "BunnyUsers", Filters.eq("userID", userId), user);
    }

    public static void addSubjectToSemester(String userId, Subject subject) {
        validate(subject);
        TimerData timerData = DB.findOne(TimerData.class, "TimerData", Filters.eq("account.userID", userId));
        if (timerData == null || timerData.getCurrentSemester() == null || timerData.getCurrentSemester().getSemesterName() == null)
            throw new StateFailure("No active semester found.");

        boolean exists = timerData.getCurrentSemester().getSemesterSubjects().stream()
                .anyMatch(s -> s.getSubjectCode().equalsIgnoreCase(subject.getSubjectCode()));

        if (exists)
            throw new InputFailure(
                    "Subject '" + subject.getSubjectCode() + "' is already in the current semester.");

        timerData.getCurrentSemester().getSemesterSubjects().add(subject);
        DB.save(TimerData.class, "TimerData", Filters.eq("account.userID", userId), timerData);
    }

    public static void removeSubjectFromSemester(String userId, String subjectCode) {
        TimerData timerData = DB.findOne(TimerData.class, "TimerData", Filters.eq("account.userID", userId));
        if (timerData == null || timerData.getCurrentSemester() == null || timerData.getCurrentSemester().getSemesterName() == null)
            throw new StateFailure("No active semester found.");

        if (timerData.getSessionData().getSessionStartTime() != null)
            throw new StateFailure("End your study session before removing a semester course.");

        boolean removed = timerData.getCurrentSemester().getSemesterSubjects()
                .removeIf(s -> matches(s, subjectCode));

        if (!removed)
            throw new InputFailure("You haven't registered '" + subjectCode + "' for this semester.");

        DB.save(TimerData.class, "TimerData", Filters.eq("account.userID", userId), timerData);
    }

    static void validate(Subject subject) {
        if (subject == null || subject.getSubjectCode() == null || subject.getSubjectCode().isBlank()
                || subject.getSubjectCode().length() > 24 || subject.getSubjectCode().contains(" - "))
            throw new InputFailure("Course code must contain 1 to 24 characters without ' - '.");
        if (subject.getSubjectName() == null || subject.getSubjectName().isBlank() || subject.getSubjectName().length() > 70)
            throw new InputFailure("Course name must contain 1 to 70 characters.");
        if (subject.getCreditHours() < 1 || subject.getCreditHours() > 30)
            throw new InputFailure("Credit hours must be between 1 and 30.");
        if (subject.getGrade() != null && subject.getGradeEnum() == null)
            throw new InputFailure("Unknown grade.");
        subject.setSubjectCode(subject.getSubjectCode().trim().toUpperCase(java.util.Locale.ROOT));
        subject.setSubjectName(subject.getSubjectName().trim());
    }
}
