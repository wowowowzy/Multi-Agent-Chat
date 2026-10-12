package com.example.agentscopegroupchat.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentscopegroupchat.config.AppProperties;
import com.example.agentscopegroupchat.model.EmbeddingClient;
import com.example.agentscopegroupchat.model.RerankClient;
import com.example.agentscopegroupchat.store.MemoryDao;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 单轮召回备忘（{@link RecallMemo}）的去重契约。
 *
 * <p><b>为什么需要这个测试：</b>一轮里 N 个 agent 入选发言时，recall 的 queryText 是同一句话。
 * 若不做备忘，同一段文本会被 embed N 次（N 次 HTTP 调用 + N 次 input token），
 * 而且「群共识」这一路 KNN 检索也会重复跑 N 次。这里钉死：共用同一 memo 时，
 * embedding 与 searchShared 只发生一次，而 per-agent 的 searchImpressions 仍各一次。
 */
class RecallMemoTest {

    private static final String GROUP = "default";
    private static final String QUERY = "今天大家怎么都不说话";
    private static final float[] VEC = {0.1f, 0.2f, 0.3f};

    private MemoryDao memoryDao;
    private VectorStore vectorStore;
    private EmbeddingClient embeddingClient;
    private MemoryRetriever retriever;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getMemory().setEnabled(true);
        props.getMemory().setRecallTopK(10);
        props.getMemory().setRerankTopK(3);

        memoryDao = mock(MemoryDao.class);
        vectorStore = mock(VectorStore.class);
        embeddingClient = mock(EmbeddingClient.class);
        RerankClient rerankClient = mock(RerankClient.class);

        when(embeddingClient.isEnabled()).thenReturn(true);
        when(embeddingClient.embed(QUERY)).thenReturn(new EmbeddingClient.EmbeddingResult(VEC, 7));
        when(rerankClient.isEnabled()).thenReturn(false);

        // 群共识命中一条；每个 agent 的印象命中一条
        when(vectorStore.searchShared(eq(GROUP), any(), anyInt()))
                .thenReturn(List.of(new VectorStore.Scored(1L, 0.9)));
        when(memoryDao.sharedFactText(1L)).thenReturn("大家最近在忙项目");
        when(vectorStore.searchImpressions(anyString(), any(), anyInt()))
                .thenReturn(List.of(new VectorStore.Scored(2L, 0.8)));
        when(memoryDao.impressionText(2L)).thenReturn("我对你有好感");

        retriever = new MemoryRetriever(props, memoryDao, vectorStore, embeddingClient, rerankClient);
    }

    @Test
    void reusesEmbeddingAndSharedKnNAcrossAgentsInOneRound() {
        RecallMemo memo = new RecallMemo(QUERY);

        List<String> a1 = retriever.recall(GROUP, "genki", QUERY, memo);
        List<String> a2 = retriever.recall(GROUP, "kohai", QUERY, memo);

        // 同一段 query：只 embed 一次、只查一次群共识
        verify(embeddingClient, times(1)).embed(QUERY);
        verify(vectorStore, times(1)).searchShared(eq(GROUP), any(), anyInt());
        // 个人印象按 agent 各查一次（两个 agent → 两次）
        verify(vectorStore, times(2)).searchImpressions(anyString(), any(), anyInt());

        // 两个 agent 都拿到了同一条群共识，且各自有印象
        assertEquals(2, a1.size());
        assertEquals(2, a2.size());
        assertEquals("【群共识】大家最近在忙项目", a1.get(0));
        assertEquals("【群共识】大家最近在忙项目", a2.get(0));
    }

    @Test
    void memoDoesNotCrossContaminateDifferentQueries() {
        RecallMemo memo = new RecallMemo(QUERY);
        retriever.recall(GROUP, "genki", QUERY, memo);
        when(embeddingClient.embed("另一句话")).thenReturn(new EmbeddingClient.EmbeddingResult(VEC, 5));
        retriever.recall(GROUP, "genki", "另一句话", memo);

        // query 文本不一致时 memo 不命中，必须重新 embed
        verify(embeddingClient, times(1)).embed(QUERY);
        verify(embeddingClient, times(1)).embed("另一句话");
    }

    @Test
    void recallWithoutMemoStillWorks() {
        List<String> r = retriever.recall(GROUP, "genki", QUERY);
        assertEquals(2, r.size());
        verify(embeddingClient, times(1)).embed(QUERY);
    }
}