package com.example.agentscopegroupchat.dto;

/**
 * 群聊条目（groups 表 + 汇总信息），用于群列表 / 群信息。
 *
 * <p>{@code memberCount / messageCount / lastMessage*} 是列表查询里的聚合字段，
 * 不是表里的列。
 */
public class GroupDto {

    private String id;
    private String name;
    private String avatar;
    private String notice;
    private boolean sample;
    private String createdAt;
    private String updatedAt;

    /** 启用中的成员数 */
    private int memberCount;
    /** 消息总数（含系统提示条） */
    private int messageCount;
    /** 最后一条消息内容（列表预览用） */
    private String lastMessage;
    /** 最后一条消息的发送者昵称 */
    private String lastSender;
    private String lastMessageAt;
    /** 该群是否已停止聊天 */
    private boolean stopped;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getNotice() {
        return notice;
    }

    public void setNotice(String notice) {
        this.notice = notice;
    }

    public boolean isSample() {
        return sample;
    }

    public void setSample(boolean sample) {
        this.sample = sample;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public int getMemberCount() {
        return memberCount;
    }

    public void setMemberCount(int memberCount) {
        this.memberCount = memberCount;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(int messageCount) {
        this.messageCount = messageCount;
    }

    public String getLastMessage() {
        return lastMessage;
    }

    public void setLastMessage(String lastMessage) {
        this.lastMessage = lastMessage;
    }

    public String getLastSender() {
        return lastSender;
    }

    public void setLastSender(String lastSender) {
        this.lastSender = lastSender;
    }

    public String getLastMessageAt() {
        return lastMessageAt;
    }

    public void setLastMessageAt(String lastMessageAt) {
        this.lastMessageAt = lastMessageAt;
    }

    public boolean isStopped() {
        return stopped;
    }

    public void setStopped(boolean stopped) {
        this.stopped = stopped;
    }
}