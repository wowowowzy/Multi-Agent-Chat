package com.example.agentscopegroupchat.memory;

import com.example.agentscopegroupchat.config.AppProperties;
import com.example.agentscopegroupchat.dto.AgentImpression;
import com.example.agentscopegroupchat.dto.SharedFact;
import com.example.agentscopegroupchat.model.EmbeddingClient;
import com.example.agentscopegroupchat.model.RerankClient;
import com.example.agentscopegroupchat.store.MemoryDao;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 记忆召回（4.5）：embedding 向量检索 top-10 → rerank 取 top-3 → 注入发言 prompt。
 *
 * <p>rerank 未配置则跳过，直接用向量分数排序。embedding 未配置时退化为取最近记忆。
 */
@Component
public class MemoryRetriever {

    private static final String TAG_SHARED = "群共识";
    private static final String TAG_IMPRESSION = "我的印象";

    private record Candidate(String tag, String text) {
    }

    private final AppProperties props;
    private final MemoryDao memoryDao;
    private final VectorStore vectorStore;
    private final EmbeddingClient embeddingClient;
    private final RerankClient rerankClient;

    public MemoryRetriever(AppProperties props, MemoryDao memoryDao, VectorStore vectorStore,
            EmbeddingClient embeddingClient, RerankClient rerankClient) {
        this.props = props;
        this.memoryDao = memoryDao;
        this.vectorStore = vectorStore;
        this.embeddingClient = embeddingClient;
        this.rerankClient = rerankClient;
    }

    /** 召回与当前语境最相关的记忆文本（已按 tag 标注），供发言 prompt 注入。 */
    public List<String> recall(String groupId, String agentId, String queryText) {
        return recall(groupId, agentId, queryText, null);
    }

    /**
     * 带单轮备忘的召回：query 向量与「群共识」检索跨 agent 复用（见 {@link RecallMemo}）。
     *
     * <p>同一轮里对所有入选 agent，{@code queryText}/{@code groupId} 相同，只有
     * {@code agentId} 不同，因此只有「我的印象」那一路（以及 rerank）需要按 agent 各算一次。
     */
    public List<String> recall(String groupId, String agentId, String queryText, RecallMemo memo) {
        AppProperties.Memory cfg = props.getMemory();
        if (!cfg.isEnabled()) {
            return List.of();
        }
        int topK = cfg.getRecallTopK();
        int finalK = cfg.getRerankTopK();

        boolean useMemo = memo != null && memo.matches(queryText);
        float[] query;
        List<String> sharedTexts;
        if (useMemo && memo.isReady()) {
            query = memo.query();
            sharedTexts = memo.sharedTexts();
        } else {
            query = embeddingClient.isEnabled() ? embedding(queryText) : null;
            sharedTexts = sharedTexts(groupId, query, topK);
            if (useMemo) {
                memo.fillOnce(query, sharedTexts);
            }
        }

        List<Candidate> candidates = new ArrayList<>();
        for (String text : sharedTexts) {
            candidates.add(new Candidate(TAG_SHARED, text));
        }
        if (query != null) {
            for (VectorStore.Scored s : vectorStore.searchImpressions(agentId, query, topK)) {
                String text = memoryDao.impressionText(s.id());
                if (text != null) {
                    candidates.add(new Candidate(TAG_IMPRESSION, text));
                }
            }
        } else {
            for (AgentImpression i : memoryDao.recentImpressions(agentId, topK)) {
                candidates.add(new Candidate(TAG_IMPRESSION, i.getImpression()));
            }
        }

        if (candidates.isEmpty()) {
            return List.of();
        }

        List<Candidate> ordered = candidates;
        if (rerankClient.isEnabled()) {
            List<String> docs = candidates.stream().map(Candidate::text).toList();
            List<RerankClient.Reranked> reranked = rerankClient.rerank(queryText, docs, finalK);
            if (reranked != null && !reranked.isEmpty()) {
                List<Candidate> tmp = new ArrayList<>();
                for (RerankClient.Reranked r : reranked) {
                    if (r.index() >= 0 && r.index() < candidates.size()) {
                        tmp.add(candidates.get(r.index()));
                    }
                }
                if (!tmp.isEmpty()) {
                    ordered = tmp;
                }
            }
        }

        List<String> out = new ArrayList<>();
        for (Candidate c : ordered) {
            if (out.size() >= finalK) {
                break;
            }
            out.add("【" + c.tag() + "】" + c.text());
        }
        return out;
    }

    /**
     * 「群共识」候选文本：有向量走 KNN，无向量（embedding 未配置/失败）退化为最近若干条。
     * 同一轮内对所有 agent 完全相同，故可放进 {@link RecallMemo} 复用。
     */
    private List<String> sharedTexts(String groupId, float[] query, int topK) {
        List<String> out = new ArrayList<>();
        if (query != null) {
            for (VectorStore.Scored s : vectorStore.searchShared(groupId, query, topK)) {
                String text = memoryDao.sharedFactText(s.id());
                if (text != null) {
                    out.add(text);
                }
            }
        } else {
            for (SharedFact f : memoryDao.recentSharedFacts(groupId, topK)) {
                out.add(f.getFact());
            }
        }
        return out;
    }

    private float[] embedding(String text) {
        EmbeddingClient.EmbeddingResult r = embeddingClient.embed(text);
        return r == null ? null : r.vector();
    }
}