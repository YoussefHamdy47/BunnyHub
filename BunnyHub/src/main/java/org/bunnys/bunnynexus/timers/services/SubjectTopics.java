package org.bunnys.bunnynexus.timers.services;

import org.bunnys.handler.utils.InteractionErrors.InputFailure;

import org.bunnys.database.models.timers.Subject;
import java.util.List;
import java.util.Locale;

/** The display separator is deliberately distinct from hyphens inside course codes. */
public final class SubjectTopics {
    private SubjectTopics() {}

    public static String code(String topic) {
        if (topic == null || topic.isBlank())
            throw new InputFailure("Choose a course from your current semester.");
        String code = topic.split("\\s+-\\s+", 2)[0].trim().toUpperCase(Locale.ROOT);
        if (code.isBlank() || code.equals("-"))
            throw new InputFailure("Course code cannot be empty.");
        return code;
    }

    public static Subject require(List<Subject> subjects, String topic) {
        if (topic != null && topic.length() > 100)
            throw new InputFailure("Course selection must contain at most 100 characters.");
        String code = code(topic);
        return subjects.stream().filter(s -> code.equalsIgnoreCase(s.getSubjectCode())).findFirst()
                .orElseThrow(() -> new InputFailure("Course '" + code + "' is not in your current semester."));
    }
}
