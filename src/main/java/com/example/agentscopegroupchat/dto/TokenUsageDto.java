package com.example.agentscopegroupchat.dto;

/** Token 流水条目（token_usage 表）。 */
public class TokenUsageDto {

    public static final String PURPOSE_GATE = "GATE";
    public static final String PURPOSE_SPEAK = "SPEAK";
    public static final String PURPOSE_MEMORY = "MEMORY";
    public static final String PURPOSE_EMBED = "EMBED";

    private long id;
    private String ts;
    /** 流水归属的群（多群统计用）；记忆/向量这类群内调用也带上群 id。 */
    private String groupId;
    private String agentId;
    private String purpose;
    private int inputTokens;
    private int outputTokens;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getTs() {
        return ts;
    }

    public void setTs(String ts) {
        this.ts = ts;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(int inputTokens) {
        this.inputTokens = inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(int outputTokens) {
        this.outputTokens = outputTokens;
    }
}