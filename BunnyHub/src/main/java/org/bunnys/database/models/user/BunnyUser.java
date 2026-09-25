package org.bunnys.database.models.user;

import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.codecs.pojo.annotations.BsonProperty;
import org.bson.types.ObjectId;
import org.bunnys.database.models.timers.Grade;
import org.bunnys.database.models.timers.Subject;

import java.util.ArrayList;
import java.util.List;

public class BunnyUser implements org.bunnys.handler.database.VersionedDocument {
    private Long revision;
    public Long getRevision() { return revision; }
    public void setRevision(Long revision) { this.revision = revision; }
    @BsonId
    private ObjectId id; // Native Mongo _id

    private String userID; // The Discord ID

    @BsonProperty("Rank")
    private int rank;

    @BsonProperty("RP")
    private long rp;

    @BsonProperty("Subjects")
    private List<Subject> subjects = new ArrayList<>();

    public BunnyUser() {}

    public ObjectId getId() { return id; }
    public void setId(ObjectId id) { this.id = id; }

    public String getUserID() { return userID; }
    public void setUserID(String userID) { this.userID = userID; }

    public int getRank() { return rank; }
    public void setRank(int rank) { this.rank = rank; }

    public long getRp() { return rp; }
    public void setRp(long rp) { this.rp = rp; }

    public List<Subject> getSubjects() { return subjects; }
    public void setSubjects(List<Subject> subjects) { this.subjects = subjects == null ? new ArrayList<>() : subjects; }

    public double calculateCumulativeGPA() {
        if (subjects == null || subjects.isEmpty()) return 0.0;

        double totalPoints = 0;
        int totalCreditHours = 0;

        for (Subject subject : subjects) {
            Grade grade = subject.getGradeEnum();

            if (grade != null && grade != Grade.W && grade != Grade.P && subject.getCreditHours() > 0) {
                totalPoints += grade.getGpaValue() * subject.getCreditHours();
                totalCreditHours += subject.getCreditHours();
            }
        }

        return totalCreditHours == 0 ? 0.0 : totalPoints / totalCreditHours;
    }
}
