package org.bunnys.database.models.timers;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class Session {
    private String guildID;
    private String channelID;
    private String messageID;
    private Date sessionStartTime = null;
    private String sessionTopic;
    private double sessionTime = 0.0;
    private int numberOfBreaks = 0;
    private SessionBreak sessionBreaks = new SessionBreak(); // Replicates default: () => ({})
    private String lastSessionTopic = null;
    private Date lastSessionDate = null;
    private List<String> subjectsStudied = new ArrayList<>();

    public Session() {
    }

    // Getters and Setters
    public String getGuildID() {
        return guildID;
    }

    public void setGuildID(String guildID) {
        this.guildID = guildID;
    }

    public String getChannelID() {
        return channelID;
    }

    public void setChannelID(String channelID) {
        this.channelID = channelID;
    }

    public String getMessageID() {
        return messageID;
    }

    public void setMessageID(String messageID) {
        this.messageID = messageID;
    }

    public Date getSessionStartTime() {
        return sessionStartTime;
    }

    public void setSessionStartTime(Date sessionStartTime) {
        this.sessionStartTime = sessionStartTime;
    }

    public String getSessionTopic() {
        return sessionTopic;
    }

    public void setSessionTopic(String sessionTopic) {
        this.sessionTopic = sessionTopic;
    }

    public double getSessionTime() {
        return sessionTime;
    }

    public void setSessionTime(double sessionTime) {
        this.sessionTime = Math.round(sessionTime * 1000.0) / 1000.0;
    }

    public int getNumberOfBreaks() {
        return numberOfBreaks;
    }

    public void setNumberOfBreaks(int numberOfBreaks) {
        this.numberOfBreaks = numberOfBreaks;
    }

    public SessionBreak getSessionBreaks() {
        return sessionBreaks;
    }

    public void setSessionBreaks(SessionBreak sessionBreaks) {
        this.sessionBreaks = sessionBreaks == null ? new SessionBreak() : sessionBreaks;
    }

    public String getLastSessionTopic() {
        return lastSessionTopic;
    }

    public void setLastSessionTopic(String lastSessionTopic) {
        this.lastSessionTopic = lastSessionTopic;
    }

    public Date getLastSessionDate() {
        return lastSessionDate;
    }

    public void setLastSessionDate(Date lastSessionDate) {
        this.lastSessionDate = lastSessionDate;
    }

    public List<String> getSubjectsStudied() {
        return subjectsStudied;
    }

    public void setSubjectsStudied(List<String> subjectsStudied) {
        this.subjectsStudied = subjectsStudied == null ? new ArrayList<>() : subjectsStudied;
    }
}