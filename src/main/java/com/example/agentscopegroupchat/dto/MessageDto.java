package com.example.agentscopegroupchat.dto;

/** 消息账本条目（messages 表）。 */
public class MessageDto {

    public static final String TYPE_HUMAN = "HUMAN";
    public static final String TYPE_AGENT = "AGENT";
    public static final String TYPE_SYSTEM = "SYSTEM";

    private long id;
    private String groupId;
    private String senderId;
    private String senderType;
    private String senderName;
    private String avatar;
    private String content;
    private boolean recalled;
    private String createdAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public String getSenderType() {
        return senderType;
    }

    public void setSenderType(String senderType) {
        this.senderType = senderType;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isRecalled() {
        return recalled;
    }

    public void setRecalled(boolean recalled) {
        this.recalled = recalled;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}