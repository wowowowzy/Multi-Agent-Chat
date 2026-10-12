package com.example.agentscopegroupchat.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆提取的结构化输出（4.5）：一次调用同时产出两层记忆。
 *
 * <ul>
 *   <li>{@code sharedFacts}：群共享事实（所有 agent 共用）</li>
 *   <li>{@code impressionsByAgent}：以 agentId 为 key 的个人印象列表</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemoryExtraction {

    private List<String> sharedFacts = new ArrayList<>();
    private Map<String, List<String>> impressionsByAgent = new HashMap<>();

    public List<String> getSharedFacts() {
        return sharedFacts;
    }

    public void setSharedFacts(List<String> sharedFacts) {
        this.sharedFacts = sharedFacts == null ? new ArrayList<>() : sharedFacts;
    }

    public Map<String, List<String>> getImpressionsByAgent() {
        return impressionsByAgent;
    }

    public void setImpressionsByAgent(Map<String, List<String>> impressionsByAgent) {
        this.impressionsByAgent = impressionsByAgent == null ? new HashMap<>() : impressionsByAgent;
    }
}