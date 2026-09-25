package org.bunnys.database.models.timers;

import org.bson.codecs.pojo.annotations.BsonIgnore;

public class Subject {
    private String subjectName;
    private String subjectCode;
    private int timesStudied;
    private String grade;
    private Integer marksLost;
    private int creditHours;
    private Double totalStudyTime;

    public Subject() {
    }

    public String getSubjectName() {
        return subjectName;
    }

    public void setSubjectName(String subjectName) {
        this.subjectName = subjectName;
    }

    public String getSubjectCode() {
        return subjectCode;
    }

    public void setSubjectCode(String subjectCode) {
        this.subjectCode = subjectCode;
    }

    public int getTimesStudied() {
        return timesStudied;
    }

    public void setTimesStudied(int timesStudied) {
        this.timesStudied = timesStudied;
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }

    public Integer getMarksLost() {
        return marksLost;
    }

    public void setMarksLost(Integer marksLost) {
        this.marksLost = marksLost;
    }

    public int getCreditHours() {
        return creditHours;
    }

    public void setCreditHours(int creditHours) {
        this.creditHours = creditHours;
    }

    public Double getTotalStudyTime() {
        return totalStudyTime;
    }

    public void setTotalStudyTime(Double totalStudyTime) {
        this.totalStudyTime = totalStudyTime;
    }

    @BsonIgnore
    public Grade getGradeEnum() {
        return grade != null ? Grade.fromString(grade) : null;
    }
}