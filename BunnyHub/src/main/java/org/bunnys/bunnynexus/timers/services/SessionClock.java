package org.bunnys.bunnynexus.timers.services;

import org.bunnys.database.models.timers.Session;

/**
 * Live timings of a running session, in seconds. Every screen and the final recap derive them here, so a
 * backward wall-clock adjustment cannot make a break negative or un-credit study time already allocated to a
 * subject (the {@code sessionTime} watermark).
 *
 * @param elapsed      wall time since the session started
 * @param breaks       finished breaks plus the one still open
 * @param openBreak    the break still running, or 0
 * @param activeStudy  study time excluding breaks, never below the allocation watermark
 */
public record SessionClock(double elapsed, double breaks, double openBreak, double activeStudy) {

    /** Requires a started session. */
    public static SessionClock of(Session session, long nowMillis) {
        double elapsed = Math.max(0, nowMillis - session.getSessionStartTime().getTime()) / 1000.0;
        var breakStart = session.getSessionBreaks().getSessionBreakStart();
        double open = breakStart == null ? 0 : Math.max(0, nowMillis - breakStart.getTime()) / 1000.0;
        double breaks = session.getSessionBreaks().getSessionBreakTime() + open;
        double active = Math.max(session.getSessionTime(), Math.max(0, elapsed - breaks));
        return new SessionClock(elapsed, breaks, open, active);
    }
}
