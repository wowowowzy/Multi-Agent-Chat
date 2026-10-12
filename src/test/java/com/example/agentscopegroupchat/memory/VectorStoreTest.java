package com.example.agentscopegroupchat.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.agentscopegroupchat.config.AppProperties;
import com.example.agentscopegroupchat.config.SqliteDataSource;
import com.example.agentscopegroupchat.store.MemoryDao;
import com.example.agentscopegroupchat.util.FloatBlobUtil;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 向量检索兜底路径测试：关闭 sqlite-vec 扩展，强制走纯 Java 余弦暴力查，验证排序与过滤。
 *
 * <p>真实环境优先用 sqlite-vec（vec0 KNN）；两条路径共用同一份 BLOB 与分数语义
 * （score = 1 - distance / cosine），故这里验证兜底即验证了功能等价。
 */
class VectorStoreTest {

    private static final String GROUP = "default";
    private static final String AGENT = "genki";

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private MemoryDao memoryDao;
    private VectorStore store;

    @BeforeEach
    void setUp() {
        // extensionPath = null -> 不加载原生扩展；vec.enabled = false -> 强制 BRUTE 模式
        SqliteDataSource ds = new SqliteDataSource(tempDir.resolve("test.db").toString(), null);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE shared_facts (id INTEGER PRIMARY KEY AUTOINCREMENT, group_id TEXT NOT NULL "
                + "DEFAULT 'default', fact TEXT NOT NULL, embedding BLOB, created_at TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE agent_impressions (id INTEGER PRIMARY KEY AUTOINCREMENT, agent_id TEXT NOT NULL, "
                + "about_user TEXT, impression TEXT NOT NULL, embedding BLOB, created_at TEXT NOT NULL)");

        memoryDao = new MemoryDao(jdbc);
        AppProperties props = new AppProperties();
        props.getVec().setEnabled(false);
        props.getVec().setDimension(4);
        store = new VectorStore(jdbc, memoryDao, props);
        store.init();
    }

    @Test
    void fallsBackToBruteForceWhenExtensionDisabled() {
        assertEquals(VectorStore.Mode.BRUTE, store.getMode());
        assertEquals(4, store.getDimension());
    }

    @Test
    void bruteForceRanksByCosineSimilarity() {
        long id1 = insertFact("[1,0,0,0]", 1f, 0f, 0f, 0f);
        long id2 = insertFact("[0.9,0.1,0,0]", 0.9f, 0.1f, 0f, 0f);
        long id3 = insertFact("[0,1,0,0]", 0f, 1f, 0f, 0f);

        List<VectorStore.Scored> top2 = store.searchShared(GROUP, new float[] {1f, 0f, 0f, 0f}, 2);

        assertEquals(2, top2.size());
        assertEquals(id1, top2.get(0).id());
        assertEquals(id2, top2.get(1).id());
        assertTrue(top2.get(0).score() > top2.get(1).score());
        assertTrue(top2.get(1).score() > 0.9);
        // id3 明显不相关，应被 top-2 排除
        assertTrue(top2.stream().noneMatch(s -> s.id() == id3));
    }

    @Test
    void impressionsAreFilteredByAgent() {
        long mine = insertImpression(AGENT, 1f, 0f, 0f, 0f);
        insertImpression("brother", 1f, 0f, 0f, 0f); // 别人的印象，不应召回

        List<VectorStore.Scored> hits = store.searchImpressions(AGENT, new float[] {1f, 0f, 0f, 0f}, 5);

        assertEquals(1, hits.size());
        assertEquals(mine, hits.get(0).id());
    }

    @Test
    void nullQueryOrZeroKYieldsEmpty() {
        assertTrue(store.searchShared(GROUP, null, 5).isEmpty());
        assertTrue(store.searchImpressions(AGENT, new float[] {1f, 0f, 0f, 0f}, 0).isEmpty());
    }

    private long insertFact(String label, float... vec) {
        return memoryDao.insertSharedFact(GROUP, label, FloatBlobUtil.toBlob(vec));
    }

    private long insertImpression(String agentId, float... vec) {
        return memoryDao.insertImpression(agentId, "user", "impression-" + agentId, FloatBlobUtil.toBlob(vec));
    }
}