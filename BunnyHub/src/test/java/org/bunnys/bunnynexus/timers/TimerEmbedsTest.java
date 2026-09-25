package org.bunnys.bunnynexus.timers;

import net.dv8tion.jda.api.entities.User;
import org.bunnys.database.models.timers.*;
import org.bunnys.database.models.user.BunnyUser;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TimerEmbedsTest {
    @Test void academicRecordPaginatesWithoutMutatingStoredSubjectOrder() {
        var timer = new TimerData();
        var account = new BunnyUser();
        var subjects = new ArrayList<Subject>();
        for (int i = 0; i < 6; i++) {
            var subject = new Subject();
            subject.setSubjectCode("CS" + i); subject.setSubjectName("Course " + i);
            subject.setCreditHours(i + 1); subjects.add(subject);
        }
        timer.getCurrentSemester().setSemesterSubjects(subjects);
        var user = mock(User.class);
        when(user.getEffectiveName()).thenReturn("Student");
        var pages = new TimerEmbeds(timer, account, user).gpa();
        assertEquals(2, pages.size());
        assertEquals("Page 1 of 2", pages.getFirst().getFooter().getText());
        assertTrue(pages.getFirst().getDescription().contains("CS5"));
        assertFalse(pages.getFirst().getDescription().contains("CS0"));
        assertTrue(pages.getLast().getDescription().contains("CS0"));
        assertEquals("CS0", subjects.getFirst().getSubjectCode());
    }
}
