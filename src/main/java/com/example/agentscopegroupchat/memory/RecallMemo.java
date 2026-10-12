package com.example.agentscopegroupchat.memory;

import java.util.List;

/**
 * 单轮召回备忘：同一轮里所有入选 agent 的召回 queryText 都是同一条触发消息，
 * 因此 query 向量（一次 embedding HTTP 调用）与「群共识」那一路检索结果只需算一次、
 * 跨 agent 复用，避免 N 个入选 agent 重复 N 次 embedding + N 次共识 KNN。
 *
 * <p>生命周期只有一轮：由调度器在串行发射前创建、发射完即弃。因此不存在缓存陈旧
 * 或 embedding 模型热切换导致向量失效的问题。并发下懒计算只生效一次（synchronized 兜底）。
 */
public final class RecallMemo {

    private final String queryText;
    private boolean ready;
    private float[] query;
    private List<String> sharedTexts;

    public RecallMemo(String queryText) {
        this.queryText = queryText;
    }

    /** 本次召回是否可复用本备忘（query 文本一致才可复用）。 */
    public boolean matches(String text) {
        return queryText != null && queryText.equals(text);
    }

    public boolean isReady() {
        return ready;
    }

    public float[] query() {
        return query;
    }

    public List<String> sharedTexts() {
        return sharedTexts;
    }

    /** 首次计算结果并缓存；重复调用忽略（并发下只生效一次）。 */
    public synchronized void fillOnce(float[] query, List<String> sharedTexts) {
        if (!ready) {
            this.query = query;
            this.sharedTexts = sharedTexts;
            this.ready = true;
        }
    }
}