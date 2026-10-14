package org.bunnys.bunnynexus.timers.services;

import org.bson.conversions.Bson;
import org.bunnys.bunnynexus.timers.SessionEmbeds;
import org.bunnys.database.models.timers.TimerData;
import org.bunnys.handler.database.DB;
import org.junit.jupiter.api.Test;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LastBreakTest {
    @Test void telemetryMeasuresFromLatestBreakEnd() {
        var session = SessionClockTest.running(0);
        session.setNumberOfBreaks(2);
        session.getSessionBreaks().setLastBreakEnd(new Date(120_000));
        assertEquals(180, SessionClock.sinceLastBreak(session, 300_000).orElseThrow());
        assertTrue(SessionEmbeds.telemetry(session, 300_000).getDescription()
                .contains("**Time Since Last Break:** `3 minutes`"));
        assertEquals(0, SessionClock.sinceLastBreak(session, 60_000).orElseThrow());
    }

    @Test void noInventedTimeForNoBreakOrLegacyRecordsOrAnOpenBreak() {
        var session = SessionClockTest.running(0);
        assertFalse(SessionEmbeds.telemetry(session, 300_000).getDescription().contains("Time Since Last Break"));
        session.setNumberOfBreaks(1);
        assertTrue(SessionClock.sinceLastBreak(session, 300_000).isEmpty());
        session.getSessionBreaks().setLastBreakEnd(new Date(120_000));
        session.getSessionBreaks().setSessionBreakStart(new Date(240_000));
        String description = SessionEmbeds.telemetry(session, 300_000).getDescription();
        assertFalse(description.contains("Time Since Last Break"));
        assertTrue(description.contains("Currently on a break. Duration: 1 minute"));
    }

    @Test void resumeSavesLatestEndAndNewSessionClearsIt() {
        var timer = StudyStretchTest.session(600_000);
        var session = timer.getSessionData();
        session.getSessionBreaks().setLastBreakEnd(new Date(1));
        session.getSessionBreaks().setSessionBreakStart(new Date(System.currentTimeMillis() - 60_000));
        try (var db = mockStatic(DB.class)) {
            db.when(() -> DB.findOne(eq(TimerData.class), eq("TimerData"), any(Bson.class))).thenReturn(timer);
            long before = System.currentTimeMillis();
            TimerSessionService.unpauseSession("123");
            assertNotNull(session.getSessionBreaks().getLastBreakEnd());
            assertTrue(session.getSessionBreaks().getLastBreakEnd().getTime() >= before);
            assertTrue(session.getSessionBreaks().getLastBreakEnd().getTime() <= System.currentTimeMillis());
            db.verify(() -> DB.save(eq(TimerData.class), eq("TimerData"), any(Bson.class), same(timer)));
            session.setSessionStartTime(null);
            TimerSessionService.startSession("123", "message", "channel", "guild", "CS-101");
            assertNull(session.getSessionBreaks().getLastBreakEnd());
        }
    }
}
