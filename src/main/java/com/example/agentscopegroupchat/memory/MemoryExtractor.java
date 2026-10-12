package com.example.agentscopegroupchat.memory;

import com.example.agentscopegroupchat.config.AppProperties;
import com.example.agentscopegroupchat.dto.AgentDef;
import com.example.agentscopegroupchat.dto.MemoryExtraction;
import com.example.agentscopegroupchat.dto.MessageDto;
import com.example.agentscopegroupchat.dto.TokenUsageDto;
import com.example.agentscopegroupchat.model.EmbeddingClient;
import com.example.agentscopegroupchat.model.ModelProvider;
import com.example.agentscopegroupchat.store.AgentDao;
import com.example.agentscopegroupchat.store.MemoryDao;
import com.example.agentscopegroupchat.store.MessageDao;
import com.example.agentscopegroupchat.store.TokenUsageDao;
import com.example.agentscopegroupchat.util.FloatBlobUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 记忆提取（4.5）：后台总结式、一次提取调用同时产出两层记忆（群共享事实 + 个人印象）。
 *
 * <p>触发由 {@link MemoryService} 控制（累计 N 条 / 空闲 N 秒）。
 */
@Component
public class MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);

    private static final String SYS_PROMPT = """
            你是群聊记忆整理助手。阅读给定的一段群聊记录，提取值得长期记住的两类信息：
            1) sharedFacts：群共享事实——群内发生过的事件、达成的共识、反复出现的梗，所有成员都适用；
            2) impressionsByAgent：以成员 id 为 key 的个人印象——该成员对群里其他人/事的私人看法、态度、情绪。
            要求：每条一句话、精炼、去掉闲聊口水；没有可提取内容时对应字段留空数组/空对象。
            只输出 JSON，不要输出任何解释、前后缀或 Markdown 代码块。输出格式示例：
            {"sharedFacts":["大家约定周五聚餐"],"impressionsByAgent":{"zhang":["觉得老王有点啰嗦"]}}
            """;

    private final AppProperties props;
    private final ModelProvider modelProvider;
    private final AgentDao agentDao;
    private final MessageDao messageDao;
    private final MemoryDao memoryDao;
    private final VectorStore vectorStore;
    private final EmbeddingClient embeddingClient;
    private final TokenUsageDao tokenUsageDao;
    private final ObjectMapper mapper;

    public MemoryExtractor(AppProperties props, ModelProvider modelProvider, AgentDao agentDao,
            MessageDao messageDao, MemoryDao memoryDao, VectorStore vectorStore,
            EmbeddingClient embeddingClient, TokenUsageDao tokenUsageDao, ObjectMapper mapper) {
        this.props = props;
        this.modelProvider = modelProvider;
        this.agentDao = agentDao;
        this.messageDao = messageDao;
        this.memoryDao = memoryDao;
        this.vectorStore = vectorStore;
        this.embeddingClient = embeddingClient;
        this.tokenUsageDao = tokenUsageDao;
        this.mapper = mapper;
    }

    private record ExtractionCall(MemoryExtraction extraction, int inputTokens, int outputTokens) {
    }

    /**
     * 执行一次提取（若有新消息）。
     *
     * @return 写入的记忆条数
     */
    public int extract(String groupId) {
        if (!props.getMemory().isEnabled()) {
            return 0;
        }
        long lastId = memoryDao.getLastExtractMessageId(groupId);
        long maxId = messageDao.maxId(groupId);
        if (maxId <= lastId) {
            return 0;
        }
        List<MessageDto> messages = messageDao.findAfter(groupId, lastId, 200);
        if (messages.isEmpty()) {
            return 0;
        }
        Model gate = modelProvider.gate();
        if (gate == null) {
            log.warn("gate 模型未配置，跳过记忆提取");
            return 0;
        }

        ExtractionCall call = callExtract(gate, groupId, messages);
        if (call == null || call.extraction() == null) {
            // 提取失败不推进进度，下轮重试
            return 0;
        }
        if (call.inputTokens() > 0 || call.outputTokens() > 0) {
            tokenUsageDao.insert(groupId, null, TokenUsageDto.PURPOSE_MEMORY, call.inputTokens(),
                    call.outputTokens());
        }

        int written = persist(groupId, call.extraction());
        memoryDao.setLastExtractMessageId(groupId, maxId);
        log.info("记忆提取完成：处理到消息 #{}，新增 {} 条记忆", maxId, written);
        return written;
    }

    private ExtractionCall callExtract(Model gate, String groupId, List<MessageDto> messages) {
        StringBuilder transcript = new StringBuilder();
        for (MessageDto m : messages) {
            String content = m.isRecalled() ? "[该消息已被撤回]" : m.getContent();
            transcript.append(m.getSenderName()).append("：").append(content).append('\n');
        }
        String members = memberList(groupId);
        String userPrompt = "【群聊记录】\n" + transcript + "\n【成员 id 对照（impressionsByAgent 的 key 用左侧 id）】\n"
                + members + "\n请提取两类记忆。";

        ReActAgent agent = ReActAgent.builder()
                .name("memory-extractor")
                .sysPrompt(SYS_PROMPT)
                .model(gate)
                .maxIters(2)
                // 提取结果是一整份 JSON（群共识 + 多人印象），记录多时较长：
                //  - maxTokens 必须给足，否则 JSON 被截断 → 整次提取白做；
                //  - responseFormat=json_object 兜底，保证 content 是合法 JSON。
                .generateOptions(GenerateOptions.builder()
                        .maxTokens(props.getMemory().getExtractMaxTokens())
                        .responseFormat(ResponseFormat.jsonObject())
                        .build())
                .build();
        try {
            RuntimeContext ctx = RuntimeContext.builder().userId("memory").sessionId(groupId).build();
            Msg result = agent.call(List.of(new UserMessage(userPrompt)), MemoryExtraction.class, ctx).block();
            if (result == null) {
                return null;
            }
            MemoryExtraction extraction;
            try {
                extraction = result.getStructuredData(MemoryExtraction.class);
            } catch (Exception e) {
                // 部分兼容接口在模型未产出结构化结果时，getStructuredData 会直接抛异常
                // （message metadata 缺少 _structured_output），此时转到宽松 JSON 解析兜底。
                extraction = null;
            }
            if (extraction == null) {
                extraction = lenientParse(result.getTextContent());
            }
            int in = result.getUsage() == null ? 0 : result.getUsage().getInputTokens();
            int out = result.getUsage() == null ? 0 : result.getUsage().getOutputTokens();
            return new ExtractionCall(extraction, in, out);
        } catch (Exception e) {
            log.warn("记忆提取调用失败：{}", e.getMessage());
            return null;
        } finally {
            agent.close();
        }
    }

    /** 结构化输出失败时的宽松 JSON 解析兜底。 */
    private MemoryExtraction lenientParse(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return mapper.readValue(text.substring(start, end + 1), MemoryExtraction.class);
        } catch (Exception e) {
            return null;
        }
    }

    private int persist(String groupId, MemoryExtraction extraction) {
        int written = 0;
        for (String fact : extraction.getSharedFacts()) {
            if (!StringUtils.hasText(fact) || memoryDao.existsSharedFact(groupId, fact.trim())) {
                continue;
            }
            String clean = fact.trim();
            float[] vec = embed(groupId, clean);
            long id = memoryDao.insertSharedFact(groupId, clean, FloatBlobUtil.toBlob(vec));
            vectorStore.upsertSharedFact(id, groupId, vec);
            written++;
        }
        // 个人印象按 agent 存、跨群共享：同一个"人"在两个群里本来就是同一个人的记忆，
        // 所以这里不再按 group_id 隔离（群共识才是按群隔离的）。
        for (Map.Entry<String, List<String>> entry : extraction.getImpressionsByAgent().entrySet()) {
            String agentId = entry.getKey();
            AgentDef agent = agentDao.findById(agentId);
            if (agent == null) {
                continue; // 幻觉出的不存在的成员，忽略
            }
            for (String impression : entry.getValue()) {
                if (!StringUtils.hasText(impression) || memoryDao.existsImpression(agentId, impression.trim())) {
                    continue;
                }
                String clean = impression.trim();
                float[] vec = embed(groupId, clean);
                long id = memoryDao.insertImpression(agentId, null, clean, FloatBlobUtil.toBlob(vec));
                vectorStore.upsertImpression(id, agentId, vec);
                written++;
            }
        }
        return written;
    }

    private float[] embed(String groupId, String text) {
        if (!embeddingClient.isEnabled()) {
            return null;
        }
        EmbeddingClient.EmbeddingResult r = embeddingClient.embed(text);
        if (r == null) {
            return null;
        }
        if (r.tokens() > 0) {
            tokenUsageDao.insert(groupId, null, TokenUsageDto.PURPOSE_EMBED, r.tokens(), 0);
        }
        return r.vector();
    }

    /** 该群的成员 id 对照表（多群：同一个人设可能只在这个群里出现）。 */
    private String memberList(String groupId) {
        StringBuilder sb = new StringBuilder();
        for (AgentDef a : agentDao.findByGroup(groupId)) {
            sb.append(a.getId()).append('=').append(a.getName()).append('\n');
        }
        return sb.toString();
    }
}