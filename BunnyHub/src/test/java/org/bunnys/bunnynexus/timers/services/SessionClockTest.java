package org.bunnys.bunnynexus.timers.services;

import org.bunnys.database.models.timers.Session;
import org.bunnys.utils.Durations;
import org.junit.jupiter.api.Test;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;

class SessionClockTest {
    static Session running(long startMillis) {
        var session = new Session();
        session.setSessionStartTime(new Date(startMillis));
        return session;
    }

    @Test void subtractsFinishedAndOpenBreaks() {
        var session = running(0);
        session.getSessionBreaks().setSessionBreakTime(60);
        session.getSessionBreaks().setSessionBreakStart(new Date(540_000));
        var clock = SessionClock.of(session, 600_000);
        assertEquals(600, clock.elapsed());
        assertEquals(60, clock.openBreak());
        assertEquals(120, clock.breaks());
        assertEquals(480, clock.activeStudy());
    }

    @Test void backwardClockJumpsNeverGoNegativeOrUncreditAllocatedTime() {
        var session = running(1_000_000);
        session.setSessionTime(300);
        session.getSessionBreaks().setSessionBreakStart(new Date(2_000_000));
        var clock = SessionClock.of(session, 500_000);
        assertEquals(0, clock.elapsed());
        assertEquals(0, clock.openBreak());
        assertEquals(300, clock.activeStudy(), "the allocation watermark survives the rollback");
    }

    @Test void durationsMatchTheLongFormEveryScreenUses() {
        assertEquals("0s", Durations.format(0));
        assertEquals("0s", Durations.format(-5_000));
        assertEquals("0s", Durations.format(999));
        assertEquals("1 second", Durations.format(1_000));
        assertEquals("1 minute 1 second", Durations.format(61_000));
        assertEquals("1 hour 30 minutes", Durations.format(5_400_000));
        assertEquals("2 days 3 hours", Durations.format(2 * 86_400_000L + 3 * 3_600_000L));
    }
}
