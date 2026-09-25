package org.bunnys.database.models.timers;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class Semester {
    private String semesterName = null;
    private int semesterLevel = 1;
    private double semesterXP = 0.0;
    private double semesterTime = 0.0;
    private List<Subject> semesterSubjects = new ArrayList<>();
    private List<Long> sessionStartTimes = new ArrayList<>(); // TS uses Numbers for timestamps
    private double totalBreakTime = 0.0;
    private int breakCount = 0;
    private double longestSession = 0.0;
    private int longestStreak = 0;
    private int streak = 0;
    private Date lastStreakUpdate = null;

    public Semester() {
    }

    // Getters and Setters
    public String getSemesterName() {
        return semesterName;
    }

    public void setSemesterName(String semesterName) {
        this.semesterName = semesterName;
    }

    public int getSemesterLevel() {
        return semesterLevel;
    }

    public void setSemesterLevel(int semesterLevel) {
        this.semesterLevel = semesterLevel;
    }

    public double getSemesterXP() {
        return semesterXP;
    }

    public void setSemesterXP(double semesterXP) {
        this.semesterXP = semesterXP;
    }

    public double getSemesterTime() {
        return semesterTime;
    }

    public void setSemesterTime(double semesterTime) {
        this.semesterTime = Math.round(semesterTime * 1000.0) / 1000.0;
    }

    public List<Subject> getSemesterSubjects() {
        return semesterSubjects;
    }

    public void setSemesterSubjects(List<Subject> semesterSubjects) {
        this.semesterSubjects = semesterSubjects == null ? new ArrayList<>() : semesterSubjects;
    }

    public List<Long> getSessionStartTimes() {
        return sessionStartTimes;
    }

    public void setSessionStartTimes(List<Long> sessionStartTimes) {
        this.sessionStartTimes = sessionStartTimes == null ? new ArrayList<>() : sessionStartTimes;
    }

    public double getTotalBreakTime() {
        return totalBreakTime;
    }

    public void setTotalBreakTime(double totalBreakTime) {
        this.totalBreakTime = Math.round(totalBreakTime * 1000.0) / 1000.0;
    }

    public int getBreakCount() {
        return breakCount;
    }

    public void setBreakCount(int breakCount) {
        this.breakCount = breakCount;
    }

    public double getLongestSession() {
        return longestSession;
    }

    public void setLongestSession(double longestSession) {
        this.longestSession = longestSession;
    }

    public int getLongestStreak() {
        return longestStreak;
    }

    public void setLongestStreak(int longestStreak) {
        this.longestStreak = longestStreak;
    }

    public int getStreak() {
        return streak;
    }

    public void setStreak(int streak) {
        this.streak = streak;
    }

    public Date getLastStreakUpdate() {
        return lastStreakUpdate;
    }

    public void setLastStreakUpdate(Date lastStreakUpdate) {
        this.lastStreakUpdate = lastStreakUpdate;
    }
}