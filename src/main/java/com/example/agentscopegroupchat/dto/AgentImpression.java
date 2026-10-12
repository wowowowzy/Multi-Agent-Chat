package com.example.agentscopegroupchat.dto;

/**
 * Agent 个人印象（agent_impressions 表）。一人多行，用 agent_id 逻辑分区。
 *
 * <p>{@code aboutUser} 预留：当前个人印象仅来自群聊观察，未来可扩展"关于某用户"。
 */
public class AgentImpression {

    private long id;
    private String agentId;
    private String aboutUser;
    private String impression;
    private String createdAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getAboutUser() {
        return aboutUser;
    }

    public void setAboutUser(String aboutUser) {
        this.aboutUser = aboutUser;
    }

    public String getImpression() {
        return impression;
    }

    public void setImpression(String impression) {
        this.impression = impression;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}