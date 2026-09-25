package org.bunnys.bunnynexus.timers.services;

import com.mongodb.client.model.Filters;
import org.bunnys.database.models.timers.TimerData;
import org.bunnys.handler.database.DB;
import org.bunnys.utils.BunnyLog;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Run only after CommandGate access checks, on the bounded autocomplete executor. */
public final class SubjectAutocomplete {
    private SubjectAutocomplete() {}

    public static List<String> suggest(String userId, String query) {
        Objects.requireNonNull(userId); Objects.requireNonNull(query);
        try {
            var timer = DB.findOne(TimerData.class, "TimerData", Filters.eq("account.userID", userId));
            if (timer == null || timer.getCurrentSemester() == null
                    || timer.getCurrentSemester().getSemesterSubjects() == null) return List.of();
            String search = query.toLowerCase(Locale.ROOT);
            return timer.getCurrentSemester().getSemesterSubjects().stream()
                    .filter(Objects::nonNull)
                    .filter(subject -> subject.getSubjectCode() != null && subject.getSubjectName() != null)
                    .map(subject -> subject.getSubjectCode().toUpperCase(Locale.ROOT) + " - " + subject.getSubjectName())
                    .filter(choice -> choice.length() <= 100 && choice.toLowerCase(Locale.ROOT).contains(search))
                    .distinct().limit(25).toList();
        } catch (RuntimeException failure) {
            BunnyLog.error("Subject autocomplete read failed", failure);
            return List.of();
        }
    }
}
