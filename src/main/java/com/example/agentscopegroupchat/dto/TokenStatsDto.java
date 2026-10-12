package com.example.agentscopegroupchat.dto;

import java.util.ArrayList;
import java.util.List;

/** Token 统计聚合结果（管理页 Tab2，D2）。 */
public class TokenStatsDto {

    private long totalInput;
    private long totalOutput;
    private long totalTokens;
    private long todayInput;
    private long todayOutput;
    private long todayTokens;
    private List<Agg> byAgent = new ArrayList<>();
    private List<Agg> byPurpose = new ArrayList<>();
    /** 按群聊聚合（总览视图用）；点进某个群后看 byAgent。 */
    private List<Agg> byGroup = new ArrayList<>();
    /** 当前作用域：null/空表示"全部群聊"总览。 */
    private String scopeGroupId;
    private String scopeGroupName;

    public long getTotalInput() {
        return totalInput;
    }

    public void setTotalInput(long totalInput) {
        this.totalInput = totalInput;
    }

    public long getTotalOutput() {
        return totalOutput;
    }

    public void setTotalOutput(long totalOutput) {
        this.totalOutput = totalOutput;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(long totalTokens) {
        this.totalTokens = totalTokens;
    }

    public long getTodayInput() {
        return todayInput;
    }

    public void setTodayInput(long todayInput) {
        this.todayInput = todayInput;
    }

    public long getTodayOutput() {
        return todayOutput;
    }

    public void setTodayOutput(long todayOutput) {
        this.todayOutput = todayOutput;
    }

    public long getTodayTokens() {
        return todayTokens;
    }

    public void setTodayTokens(long todayTokens) {
        this.todayTokens = todayTokens;
    }

    public List<Agg> getByAgent() {
        return byAgent;
    }

    public void setByAgent(List<Agg> byAgent) {
        this.byAgent = byAgent;
    }

    public List<Agg> getByPurpose() {
        return byPurpose;
    }

    public void setByPurpose(List<Agg> byPurpose) {
        this.byPurpose = byPurpose;
    }

    public List<Agg> getByGroup() {
        return byGroup;
    }

    public void setByGroup(List<Agg> byGroup) {
        this.byGroup = byGroup;
    }

    public String getScopeGroupId() {
        return scopeGroupId;
    }

    public void setScopeGroupId(String scopeGroupId) {
        this.scopeGroupId = scopeGroupId;
    }

    public String getScopeGroupName() {
        return scopeGroupName;
    }

    public void setScopeGroupName(String scopeGroupName) {
        this.scopeGroupName = scopeGroupName;
    }

    /** 单个聚合维度（按 agent 或按用途）。 */
    public static class Agg {
        private String key;
        private String name;
        private long input;
        private long output;
        private long total;

        public Agg() {
        }

        public Agg(String key, String name, long input, long output) {
            this.key = key;
            this.name = name;
            this.input = input;
            this.output = output;
            this.total = input + output;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public long getInput() {
            return input;
        }

        public void setInput(long input) {
            this.input = input;
        }

        public long getOutput() {
            return output;
        }

        public void setOutput(long output) {
            this.output = output;
        }

        public long getTotal() {
            return total;
        }

        public void setTotal(long total) {
            this.total = total;
        }
    }
}