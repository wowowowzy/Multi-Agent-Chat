package com.example.agentscopegroupchat.dto;

/** 决策透视条目（decision_logs 表，D1）。 */
public class DecisionDto {

    public static final String OUTCOME_SELECTED = "SELECTED";
    public static final String OUTCOME_SKIPPED = "SKIPPED";
    public static final String OUTCOME_SILENT = "SILENT";

    private long id;
    private long messageId;
    private String agentId;
    private String agentName;
    private String avatar;
    private boolean wantToSpeak;
    private int eagerness;
    private String gist;
    private String outcome;
    private String reason;
    private String createdAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getMessageId() {
        return messageId;
    }

    public void setMessageId(long messageId) {
        this.messageId = messageId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public boolean isWantToSpeak() {
        return wantToSpeak;
    }

    public void setWantToSpeak(boolean wantToSpeak) {
        this.wantToSpeak = wantToSpeak;
    }

    public int getEagerness() {
        return eagerness;
    }

    public void setEagerness(int eagerness) {
        this.eagerness = eagerness;
    }

    public String getGist() {
        return gist;
    }

    public void setGist(String gist) {
        this.gist = gist;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}