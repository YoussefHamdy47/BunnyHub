package org.bunnys.database.models.timers;

public class Account {
    private String userID;
    private double lifetimeTime = 0.0;
    private Semester longestSemester = null;

    public Account() {}

    public String getUserID() { return userID; }
    public void setUserID(String userID) { this.userID = userID; }

    public double getLifetimeTime() { return lifetimeTime; }

    public void setLifetimeTime(double lifetimeTime) {
        this.lifetimeTime = Math.round(lifetimeTime * 1000.0) / 1000.0;
    }

    public Semester getLongestSemester() { return longestSemester; }

    public void setLongestSemester(Semester longestSemester) {
        this.longestSemester = longestSemester;
    }
}