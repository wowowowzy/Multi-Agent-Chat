package com.example.agentscopegroupchat.dto;

/** 聊天开关状态（C4）。 */
public class ChatStateDto {

    private String groupId;
    private boolean stopped;
    private String updatedAt;

    public ChatStateDto() {
    }

    public ChatStateDto(String groupId, boolean stopped, String updatedAt) {
        this.groupId = groupId;
        this.stopped = stopped;
        this.updatedAt = updatedAt;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public boolean isStopped() {
        return stopped;
    }

    public void setStopped(boolean stopped) {
        this.stopped = stopped;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}