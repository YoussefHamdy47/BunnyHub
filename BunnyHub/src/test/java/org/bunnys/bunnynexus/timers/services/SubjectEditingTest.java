package org.bunnys.bunnynexus.timers.services;

import org.bson.conversions.Bson;
import org.bunnys.commands.Timer;
import org.bunnys.database.models.timers.*;
import org.bunnys.database.models.user.BunnyUser;
import org.bunnys.handler.database.DB;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubjectEditingTest {
    private Subject course(String code) {
        Subject subject = new Subject();
        subject.setSubjectCode(code);
        subject.setSubjectName("A long course name that should not become the selected code");
        subject.setCreditHours(3);
        subject.setGrade("F");
        subject.setTimesStudied(7);
        subject.setTotalStudyTime(12345.0);
        return subject;
    }

    @Test void removeAutocompleteReadsChosenRecordAndReturnsValidCodes() {
        BunnyUser user = new BunnyUser();
        user.getSubjects().add(course(" cs-101 "));
        TimerData timer = new TimerData();
        timer.getCurrentSemester().getSemesterSubjects().add(course("MATH-2"));
        var event = mock(net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent.class, RETURNS_DEEP_STUBS);
        when(event.getUser().getId()).thenReturn("123");
        when(event.getOption("destination").getAsString()).thenReturn("ACCOUNT");
        try (var db = mockStatic(DB.class)) {
            db.when(() -> DB.findOne(eq(BunnyUser.class), eq("BunnyUsers"), any(Bson.class))).thenReturn(user);
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(timer);
            var command = new Timer(null).getSubcommands().get("remove-subject");
            assertEquals(List.of("CS-101"), command.autocomplete(null, event));
            when(event.getOption("destination").getAsString()).thenReturn("SEMESTER");
            assertEquals(List.of("MATH-2"), command.autocomplete(null, event));
        }
    }

    @Test void removalAcceptsHyphensLegacyLabelsAndStoredWhitespace() {
        BunnyUser user = new BunnyUser();
        user.getSubjects().add(course(" cs-101 "));
        try (var db = mockStatic(DB.class)) {
            db.when(() -> DB.findOne(eq(BunnyUser.class), eq("BunnyUsers"), any(Bson.class))).thenReturn(user);
            TimerSubjectService.removeSubjectFromAccount("123", "CS-101 - Introduction");
            assertTrue(user.getSubjects().isEmpty());
            db.verify(() -> DB.save(eq(BunnyUser.class), eq("BunnyUsers"), any(Bson.class), same(user)));
        }
    }

    @Test void retakeUpdatesGpaAndDetailsWithoutDiscardingStudyHistory() {
        BunnyUser user = new BunnyUser();
        var original = course("CS-101");
        user.getSubjects().add(original);
        try (var db = mockStatic(DB.class)) {
            db.when(() -> DB.findOne(eq(BunnyUser.class), eq("BunnyUsers"), any(Bson.class))).thenReturn(user);
            assertEquals(0, user.calculateCumulativeGPA());
            var result = TimerSubjectService.updateSubject("123", true, "cs-101",
                    new TimerSubjectService.SubjectUpdate("cs-102", "Retaken course", 4, "A", 2, false));
            assertSame(original, result);
            assertEquals(4, user.calculateCumulativeGPA());
            assertEquals("CS-102", result.getSubjectCode());
            assertEquals(4, result.getCreditHours());
            assertEquals("Retaken course", result.getSubjectName());
            assertEquals(2, result.getMarksLost());
            assertEquals(7, result.getTimesStudied());
            assertEquals(12345, result.getTotalStudyTime());
            assertEquals(1, user.getSubjects().size());
        }
    }

    @Test void academicRecordRemovalDoesNotRequireATimerDocument() {
        var user = new BunnyUser();
        user.getSubjects().add(course("CS-101"));
        try (var db = mockStatic(DB.class)) {
            db.when(() -> DB.findOne(eq(BunnyUser.class), eq("BunnyUsers"), any(Bson.class))).thenReturn(user);
            var result = new org.bunnys.bunnynexus.timers.Timers("123", null)
                    .removeSubject(org.bunnys.bunnynexus.timers.Timers.RecordDestination.ACCOUNT, "cs-101");
            assertNotNull(result);
            assertTrue(user.getSubjects().isEmpty());
            db.verify(() -> DB.findOne(eq(TimerData.class), anyString(), any(Bson.class)), never());
        }
    }

    @Test void invalidOrDuplicateUpdatesLeaveOriginalUntouched() {
        BunnyUser user = new BunnyUser();
        var original = course("CS-101");
        user.getSubjects().addAll(List.of(original, course("CS-102")));
        try (var db = mockStatic(DB.class)) {
            db.when(() -> DB.findOne(eq(BunnyUser.class), eq("BunnyUsers"), any(Bson.class))).thenReturn(user);
            assertThrows(IllegalArgumentException.class, () -> TimerSubjectService.updateSubject("123", true, "CS-101",
                    new TimerSubjectService.SubjectUpdate("cs-102", "Changed", 4, "A", null, false)));
            assertThrows(IllegalArgumentException.class, () -> TimerSubjectService.updateSubject("123", true, "CS-101",
                    new TimerSubjectService.SubjectUpdate(null, "Changed", -1, "A", null, false)));
            assertEquals("F", original.getGrade());
            assertEquals(3, original.getCreditHours());
            assertEquals("CS-101", original.getSubjectCode());
        }
    }

    @Test void clearGradeExcludesTheCourseFromGpaAndPreservesOtherFields() {
        BunnyUser user = new BunnyUser();
        var original = course("CS-101");
        user.getSubjects().add(original);
        try (var db = mockStatic(DB.class)) {
            db.when(() -> DB.findOne(eq(BunnyUser.class), eq("BunnyUsers"), any(Bson.class))).thenReturn(user);
            TimerSubjectService.updateSubject("123", true, "CS-101",
                    new TimerSubjectService.SubjectUpdate(null, null, null, null, null, true));
            assertNull(original.getGrade());
            assertEquals(3, original.getCreditHours());
            assertEquals(7, original.getTimesStudied());
        }
    }

    @Test void cannotRenameSemesterCodeDuringActiveSession() {
        TimerData timer = new TimerData();
        timer.getCurrentSemester().setSemesterName("Fall");
        timer.getCurrentSemester().getSemesterSubjects().add(course("CS-101"));
        timer.getSessionData().setSessionStartTime(new Date());
        try (var db = mockStatic(DB.class)) {
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(timer);
            assertThrows(IllegalStateException.class, () -> TimerSubjectService.updateSubject("123", false, "CS-101",
                    new TimerSubjectService.SubjectUpdate("CS-102", null, null, null, null, false)));
            assertEquals("CS-101", timer.getCurrentSemester().getSemesterSubjects().getFirst().getSubjectCode());
        }
    }

    @Test void rejectsNoChangesAndConflictingGradeOptions() {
        assertThrows(IllegalArgumentException.class, () -> TimerSubjectService.updateSubject("123", true, "CS-101",
                new TimerSubjectService.SubjectUpdate(null, null, null, null, null, false)));
        assertThrows(IllegalArgumentException.class, () -> TimerSubjectService.updateSubject("123", true, "CS-101",
                new TimerSubjectService.SubjectUpdate(null, null, null, "A", null, true)));
    }
}
