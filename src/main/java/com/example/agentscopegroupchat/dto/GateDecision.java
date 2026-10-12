package com.example.agentscopegroupchat.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 发言门控的结构化输出（A3）。
 *
 * <p>每个 agent 独立思考后产出：是否想说 + 表达欲 0~10 + 一句话概括。
 * 使用可变 POJO（无参构造 + getter/setter）以便结构化输出反序列化。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GateDecision {

    /** 是否想发言 */
    private boolean wantToSpeak;
    /** 表达欲强度 0~10 */
    private int eagerness;
    /** 想说的话的一句话概括 */
    private String gist;

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
}