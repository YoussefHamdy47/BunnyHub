package org.bunnys.database.models.timers;

import java.util.Date;

public class SessionBreak {
    private double sessionBreakTime = 0.0;
    private Date sessionBreakStart = null;

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
}