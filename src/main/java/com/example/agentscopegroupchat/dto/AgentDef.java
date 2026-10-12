package com.example.agentscopegroupchat.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 人设卡（存 DB，agents 表；DB 为唯一真源）。
 *
 * <p>字段即人设卡：昵称 / 头像 / 人设 prompt / 性格参数（啰嗦、emoji、回应速度、表达欲基准）/ 群内关系。
 * 性格参数以 1~10 的整数表达。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentDef {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String id;
    private String name;
    private String avatar;
    private String persona;
    /** 啰嗦程度 1~10 */
    private int verbosity = 5;
    /** emoji 使用倾向 1~10 */
    private int emojiTendency = 5;
    /** 回应速度 1~10，越大越快（快嘴 0.5s / 高冷 8s） */
    private int responseSpeed = 5;
    /** 表达欲基准 1~10 */
    private int eagernessBias = 5;
    /** 与其他成员的关系 JSON，结构自由，例如 {"小美":{"familiarity":8,"affinity":7,"meme":"..."}} */
    private String relationships;
    private boolean active = true;
    private String createdAt;
    private String updatedAt;

    /** 是否"话痨型"（用于 B2 冷场主动开题）：表达欲基准 >= 7。 */
    public boolean isTalkative() {
        return eagernessBias >= 7;
    }

    /** 解析 relationships JSON 为 Map，失败返回空 Map。 */
    public Map<String, Object> relationshipsMap() {
        if (relationships == null || relationships.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return MAPPER.readValue(relationships, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

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

    public String getPersona() {
        return persona;
    }

    public void setPersona(String persona) {
        this.persona = persona;
    }

    public int getVerbosity() {
        return verbosity;
    }

    public void setVerbosity(int verbosity) {
        this.verbosity = verbosity;
    }

    public int getEmojiTendency() {
        return emojiTendency;
    }

    public void setEmojiTendency(int emojiTendency) {
        this.emojiTendency = emojiTendency;
    }

    public int getResponseSpeed() {
        return responseSpeed;
    }

    public void setResponseSpeed(int responseSpeed) {
        this.responseSpeed = responseSpeed;
    }

    public int getEagernessBias() {
        return eagernessBias;
    }

    public void setEagernessBias(int eagernessBias) {
        this.eagernessBias = eagernessBias;
    }

    public String getRelationships() {
        return relationships;
    }

    public void setRelationships(String relationships) {
        this.relationships = relationships;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
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
}