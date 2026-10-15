package org.bunnys.database.models.timers;

import java.util.Date;

/** A pause inside a study session, with its start and end times. */
public class SessionBreak {
    private double sessionBreakTime = 0.0;
    private Date sessionBreakStart = null;
    private Date lastBreakEnd = null;

    public SessionBreak() {
    }

    public double getSessionBreakTime() {
        return sessionBreakTime;
    }

    public void setSessionBreakTime(double sessionBreakTime) {
        this.sessionBreakTime = sessionBreakTime;
    }

    public Date getSessionBreakStart() {
        return sessionBreakStart;
    }

    public void setSessionBreakStart(Date sessionBreakStart) {
        this.sessionBreakStart = sessionBreakStart;
    }

    public Date getLastBreakEnd() {
        return lastBreakEnd;
    }

    public void setLastBreakEnd(Date lastBreakEnd) {
        this.lastBreakEnd = lastBreakEnd;
    }
}
