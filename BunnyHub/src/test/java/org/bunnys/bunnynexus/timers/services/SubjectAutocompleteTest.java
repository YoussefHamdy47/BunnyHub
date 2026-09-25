package org.bunnys.bunnynexus.timers.services;

import org.bson.conversions.Bson;
import org.bunnys.database.models.timers.Subject;
import org.bunnys.database.models.timers.TimerData;
import org.bunnys.handler.database.DB;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubjectAutocompleteTest {
    private Subject subject(String code, String name) {
        var value = new Subject(); value.setSubjectCode(code); value.setSubjectName(name); return value;
    }

    @Test void filtersBeforeLimitAndSkipsMalformedRowsWithoutLosingValidMatches() {
        var timer = new TimerData();
        var subjects = new ArrayList<Subject>();
        for (int i = 0; i < 30; i++) subjects.add(subject("CS" + i, "Unrelated"));
        subjects.add(null); subjects.add(subject(null, "match"));
        subjects.add(subject("x".repeat(101), "match"));
        subjects.add(subject("cs99", "Match")); subjects.add(subject("cs99", "Match"));
        timer.getCurrentSemester().setSemesterSubjects(subjects);
        try (var db = mockStatic(DB.class)) {
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenAnswer(call -> {
                String filter = ((Bson) call.getArgument(2)).toBsonDocument().toJson();
                assertTrue(filter.contains("account.userID")); assertTrue(filter.contains("123"));
                return timer;
            });
            assertEquals(java.util.List.of("CS99 - Match"), SubjectAutocomplete.suggest("123", "mAtCh"));
        }
    }

    @Test void stopsFormattingAfterTwentyFiveDistinctMatches() {
        var timer = new TimerData();
        var subjects = new ArrayList<Subject>();
        for (int i = 0; i < 25; i++) subjects.add(subject("CS" + i, "Course"));
        var unused = mock(Subject.class); subjects.add(unused);
        timer.getCurrentSemester().setSemesterSubjects(subjects);
        try (var db = mockStatic(DB.class)) {
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(timer);
            assertEquals(25, SubjectAutocomplete.suggest("123", "").size());
            verifyNoInteractions(unused);
        }
    }

    @Test void missingRecordsAndReadFailureProduceNoSuggestions() {
        try (var db = mockStatic(DB.class)) {
            assertTrue(SubjectAutocomplete.suggest("123", "").isEmpty());
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class)))
                    .thenThrow(new IllegalStateException("private-connection-details"));
            assertTrue(SubjectAutocomplete.suggest("123", "").isEmpty());
        }
    }
}
