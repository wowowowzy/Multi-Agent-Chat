package com.example.agentscopegroupchat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用配置绑定（对应 application.yml 的 app.* 段）。
 *
 * <p>YAML 仅做应用配置管理；人设数据真源在 DB（见管理页）。
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** SQLite 单文件路径，例如 data/chat.db */
    private String dbPath = "data/chat.db";

    /** 可选访问 token，留空表示不校验，用于公网暴露防护 */
    private String accessToken = "";

    private Chat chat = new Chat();

    private Memory memory = new Memory();

    private Holiday holiday = new Holiday();

    private Vec vec = new Vec();

    private Model model = new Model();

    public String getDbPath() {
        return dbPath;
    }

    public void setDbPath(String dbPath) {
        this.dbPath = dbPath;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public Chat getChat() {
        return chat;
    }

    public void setChat(Chat chat) {
        this.chat = chat;
    }

    public Memory getMemory() {
        return memory;
    }

    public void setMemory(Memory memory) {
        this.memory = memory;
    }

    public Holiday getHoliday() {
        return holiday;
    }

    public void setHoliday(Holiday holiday) {
        this.holiday = holiday;
    }

    public Vec getVec() {
        return vec;
    }

    public void setVec(Vec vec) {
        this.vec = vec;
    }

    public Model getModel() {
        return model;
    }

    public void setModel(Model model) {
        this.model = model;
    }

    /** 群聊调度相关配置。 */
    public static class Chat {
        /** 级联深度上限 */
        private int cascadeMaxDepth = 8;
        /** 单轮最多百分之多少的 agent 发言 */
        private int speakQuotaPercent = 50;
        /** 投喂最近 K 条消息 */
        private int contextWindowK = 30;
        /** 拟人打字延迟下限（毫秒） */
        private long typingDelayMinMs = 1500;
        /** 拟人打字延迟上限（毫秒） */
        private long typingDelayMaxMs = 4000;
        /** 冷场主动开题的空闲阈值（秒） */
        private int idleTopicTriggerSec = 60;
        /** 被 @ 时"装没看见"的概率（0~1，B5） */
        private double mentionIgnoreProbability = 0.05;
        /** 群 ID（多群预留，默认单群） */
        private String groupId = "default";
        /** 群名称 */
        private String groupName = "摸鱼搭子群";
        /** 单条 agent 发言最大 token（防止失控刷屏） */
        private int speakMaxTokens = 512;
        /**
         * 门控调用最大 token。门控只产出一个小 JSON，给足余量防止被截断
         * （截断会导致结构化解析失败）。
         */
        private int gateMaxTokens = 512;
        /**
         * 连续多少轮"全自动"（冷场开题及其级联）都没真人参与后，停止自发开题。
         *
         * <p>冷场开题会让群自己一直聊下去（每 60 秒一轮），但没人看的时候纯属烧 token。
         * 达到上限后暂停；真人一发言立即恢复。{@code 0} 表示不限（保持旧行为）。
         */
        private int maxIdleRoundsWithoutHuman = 3;

        public int getCascadeMaxDepth() {
            return cascadeMaxDepth;
        }

        public void setCascadeMaxDepth(int cascadeMaxDepth) {
            this.cascadeMaxDepth = cascadeMaxDepth;
        }

        public int getSpeakQuotaPercent() {
            return speakQuotaPercent;
        }

        public void setSpeakQuotaPercent(int speakQuotaPercent) {
            this.speakQuotaPercent = speakQuotaPercent;
        }

        public int getContextWindowK() {
            return contextWindowK;
        }

        public void setContextWindowK(int contextWindowK) {
            this.contextWindowK = contextWindowK;
        }

        public long getTypingDelayMinMs() {
            return typingDelayMinMs;
        }

        public void setTypingDelayMinMs(long typingDelayMinMs) {
            this.typingDelayMinMs = typingDelayMinMs;
        }

        public long getTypingDelayMaxMs() {
            return typingDelayMaxMs;
        }

        public void setTypingDelayMaxMs(long typingDelayMaxMs) {
            this.typingDelayMaxMs = typingDelayMaxMs;
        }

        public int getIdleTopicTriggerSec() {
            return idleTopicTriggerSec;
        }

        public void setIdleTopicTriggerSec(int idleTopicTriggerSec) {
            this.idleTopicTriggerSec = idleTopicTriggerSec;
        }

        public double getMentionIgnoreProbability() {
            return mentionIgnoreProbability;
        }

        public void setMentionIgnoreProbability(double mentionIgnoreProbability) {
            this.mentionIgnoreProbability = mentionIgnoreProbability;
        }

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public String getGroupName() {
            return groupName;
        }

        public void setGroupName(String groupName) {
            this.groupName = groupName;
        }

        public int getSpeakMaxTokens() {
            return speakMaxTokens;
        }

        public void setSpeakMaxTokens(int speakMaxTokens) {
            this.speakMaxTokens = speakMaxTokens;
        }

        public int getGateMaxTokens() {
            return gateMaxTokens;
        }

        public void setGateMaxTokens(int gateMaxTokens) {
            this.gateMaxTokens = gateMaxTokens;
        }

        public int getMaxIdleRoundsWithoutHuman() {
            return maxIdleRoundsWithoutHuman;
        }

        public void setMaxIdleRoundsWithoutHuman(int maxIdleRoundsWithoutHuman) {
            this.maxIdleRoundsWithoutHuman = maxIdleRoundsWithoutHuman;
        }
    }

    /** 长期记忆（RAG + rerank）配置。 */
    public static class Memory {
        private boolean enabled = true;
        /** 累计 N 条消息触发提取 */
        private int triggerMsgCount = 50;
        /** 或空闲 N 秒触发提取 */
        private int triggerIdleSec = 300;
        /** 向量召回 top-K */
        private int recallTopK = 10;
        /** rerank 后保留 top-K */
        private int rerankTopK = 3;
        /**
         * 记忆提取调用最大 token。提取结果是一整份 JSON（群共识 + 多人印象），
         * 记录多时容易变长，必须给足余量，否则 JSON 被截断会导致整次提取失败。
         */
        private int extractMaxTokens = 2048;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getTriggerMsgCount() {
            return triggerMsgCount;
        }

        public void setTriggerMsgCount(int triggerMsgCount) {
            this.triggerMsgCount = triggerMsgCount;
        }

        public int getTriggerIdleSec() {
            return triggerIdleSec;
        }

        public void setTriggerIdleSec(int triggerIdleSec) {
            this.triggerIdleSec = triggerIdleSec;
        }

        public int getRecallTopK() {
            return recallTopK;
        }

        public void setRecallTopK(int recallTopK) {
            this.recallTopK = recallTopK;
        }

        public int getRerankTopK() {
            return rerankTopK;
        }

        public void setRerankTopK(int rerankTopK) {
            this.rerankTopK = rerankTopK;
        }

        public int getExtractMaxTokens() {
            return extractMaxTokens;
        }

        public void setExtractMaxTokens(int extractMaxTokens) {
            this.extractMaxTokens = extractMaxTokens;
        }
    }

    /**
     * 中国法定节假日 / 调休查询配置（外部接口，可选）。
     *
     * <p>接口形如 {@code {baseUrl}/{yyyy-MM-dd}}，返回 {@code isHoliday / isWorkday / holiday.name}。
     * 拿不到时自动降级为"周一~周五=工作日"，因此这是纯增强项，不影响启动与运行。
     */
    public static class Holiday {
        private boolean enabled = true;
        /** 接口前缀，实际请求 {@code baseUrl + "/" + yyyy-MM-dd}。 */
        private String baseUrl = "https://api.apisbo.com/holidays/date";
        private int timeoutMs = 2500;
        /** 查询失败后的重试间隔（秒），避免每次拼 prompt 都去打接口。 */
        private int retrySeconds = 600;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getRetrySeconds() {
            return retrySeconds;
        }

        public void setRetrySeconds(int retrySeconds) {
            this.retrySeconds = retrySeconds;
        }
    }

    /** sqlite-vec 向量扩展配置。 */
    public static class Vec {
        /** 是否尝试启用 sqlite-vec（加载失败自动降级纯 Java 余弦） */
        private boolean enabled = true;
        /** 向量维度，需与 embedding 模型一致 */
        private int dimension = 1536;
        /**
         * 扩展动态库路径；留空时自动从 classpath 的 native/ 目录提取内置 DLL。
         * 传 "none" 可强制关闭扩展、直接走纯 Java 兜底。
         */
        private String extensionPath = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }

        public String getExtensionPath() {
            return extensionPath;
        }

        public void setExtensionPath(String extensionPath) {
            this.extensionPath = extensionPath;
        }
    }

    /** 各用途模型配置（统一 OpenAI 兼容接口）。 */
    public static class Model {
        private Endpoint chat = new Endpoint();
        private Endpoint gate = new Endpoint();
        private Endpoint embedding = new Endpoint();
        private Endpoint rerank = new Endpoint();

        public Endpoint getChat() {
            return chat;
        }

        public void setChat(Endpoint chat) {
            this.chat = chat;
        }

        public Endpoint getGate() {
            return gate;
        }

        public void setGate(Endpoint gate) {
            this.gate = gate;
        }

        public Endpoint getEmbedding() {
            return embedding;
        }

        public void setEmbedding(Endpoint embedding) {
            this.embedding = embedding;
        }

        public Endpoint getRerank() {
            return rerank;
        }

        public void setRerank(Endpoint rerank) {
            this.rerank = rerank;
        }
    }

    /** 单个模型端点配置。 */
    public static class Endpoint {
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "";
        /**
         * 是否在请求体中附带 {@code enable_thinking=false}。
         *
         * <p>阿里云 MaaS/百炼网关上的 qwen3.8、deepseek 等为"思考模式"，该模式下
         * {@code tool_choice=required} 会被拒（HTTP 400）；而 AgentScope 的结构化输出
         * （门控判断、记忆提取）正是靠 tool_choice=required 实现，故默认关闭思考。
         * 换成不支持该参数的厂商可置 false。
         */
        private boolean disableThinking = true;

        public boolean isConfigured() {
            return model != null && !model.isBlank();
        }

        public boolean isDisableThinking() {
            return disableThinking;
        }

        public void setDisableThinking(boolean disableThinking) {
            this.disableThinking = disableThinking;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }
}