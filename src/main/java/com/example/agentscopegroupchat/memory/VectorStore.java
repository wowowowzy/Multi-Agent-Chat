package com.example.agentscopegroupchat.memory;

import com.example.agentscopegroupchat.config.AppProperties;
import com.example.agentscopegroupchat.store.MemoryDao;
import com.example.agentscopegroupchat.util.FloatBlobUtil;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 向量存储：sqlite-vec（vec0 虚拟表 KNN）+ 纯 Java 余弦暴力查兜底（4.5）。
 *
 * <p>启动时尝试创建 vec0 虚拟表；扩展加载失败则自动降级为暴力查，行为一致、仅稍慢。
 * 两条路径共用同一份小端序 float32 BLOB 数据。
 */
@Component
public class VectorStore {

    private static final Logger log = LoggerFactory.getLogger(VectorStore.class);

    /** 存储模式。 */
    public enum Mode {
        /** sqlite-vec KNN */
        VEC,
        /** 纯 Java 余弦兜底 */
        BRUTE
    }

    /** 检索结果：记忆行 id + 相似度分数（越大越相关）。 */
    public record Scored(long id, double score) {
    }

    private final JdbcTemplate jdbc;
    private final MemoryDao memoryDao;
    private final AppProperties props;

    private volatile Mode mode = Mode.BRUTE;
    private volatile int dimension = 1536;

    public VectorStore(JdbcTemplate jdbc, MemoryDao memoryDao, AppProperties props) {
        this.jdbc = jdbc;
        this.memoryDao = memoryDao;
        this.props = props;
    }

    @PostConstruct
    public void init() {
        this.dimension = props.getVec().getDimension();
        if (!props.getVec().isEnabled()) {
            log.info("sqlite-vec 已在配置中关闭，向量检索使用纯 Java 余弦兜底");
            mode = Mode.BRUTE;
            return;
        }
        try {
            jdbc.execute("CREATE VIRTUAL TABLE IF NOT EXISTS vec_shared_facts USING vec0("
                    + "embedding float[" + dimension + "] distance_metric=cosine, group_id text)");
            jdbc.execute("CREATE VIRTUAL TABLE IF NOT EXISTS vec_agent_impressions USING vec0("
                    + "embedding float[" + dimension + "] distance_metric=cosine, agent_id text)");
            mode = Mode.VEC;
            log.info("sqlite-vec 已启用：vec0 KNN，维度 {}", dimension);
        } catch (Exception e) {
            mode = Mode.BRUTE;
            log.warn("sqlite-vec 初始化失败，降级为纯 Java 余弦兜底：{}", e.getMessage());
        }
    }

    public Mode getMode() {
        return mode;
    }

    public int getDimension() {
        return dimension;
    }

    // ---- 写入 ----

    public void upsertSharedFact(long rowid, String groupId, float[] vector) {
        if (mode != Mode.VEC || vector == null) {
            return;
        }
        try {
            jdbc.update("INSERT INTO vec_shared_facts (rowid, embedding, group_id) VALUES (?,?,?)",
                    rowid, FloatBlobUtil.toBlob(vector), groupId);
        } catch (Exception e) {
            log.warn("写入 vec_shared_facts 失败（rowid={}）：{}", rowid, e.getMessage());
        }
    }

    public void upsertImpression(long rowid, String agentId, float[] vector) {
        if (mode != Mode.VEC || vector == null) {
            return;
        }
        try {
            jdbc.update("INSERT INTO vec_agent_impressions (rowid, embedding, agent_id) VALUES (?,?,?)",
                    rowid, FloatBlobUtil.toBlob(vector), agentId);
        } catch (Exception e) {
            log.warn("写入 vec_agent_impressions 失败（rowid={}）：{}", rowid, e.getMessage());
        }
    }

    // ---- 删除 ----

    /** 删除一条群共识向量（解散群聊时清理）。vec0 表不支持时静默跳过：孤儿行永远不会被查到。 */
    public void deleteSharedFact(long rowid) {
        if (mode != Mode.VEC) {
            return;
        }
        try {
            jdbc.update("DELETE FROM vec_shared_facts WHERE rowid = ?", rowid);
        } catch (Exception e) {
            log.warn("删除 vec_shared_facts(rowid={}) 失败：{}", rowid, e.getMessage());
        }
    }

    // ---- 检索 ----

    /** 群共享事实 top-K。 */
    public List<Scored> searchShared(String groupId, float[] query, int k) {
        if (query == null || k <= 0) {
            return List.of();
        }
        if (mode == Mode.VEC) {
            try {
                return jdbc.query("SELECT rowid, distance FROM vec_shared_facts "
                                + "WHERE embedding MATCH ? AND k = ? AND group_id = ? ORDER BY distance",
                        (rs, rowNum) -> new Scored(rs.getLong(1), 1.0 - rs.getDouble(2)),
                        FloatBlobUtil.toBlob(query), k, groupId);
            } catch (Exception e) {
                log.warn("vec0 查询失败，改走暴力查：{}", e.getMessage());
            }
        }
        return bruteForce(memoryDao.sharedFactsWithEmbedding(groupId), query, k);
    }

    /** 某 agent 的个人印象 top-K。 */
    public List<Scored> searchImpressions(String agentId, float[] query, int k) {
        if (query == null || k <= 0) {
            return List.of();
        }
        if (mode == Mode.VEC) {
            try {
                return jdbc.query("SELECT rowid, distance FROM vec_agent_impressions "
                                + "WHERE embedding MATCH ? AND k = ? AND agent_id = ? ORDER BY distance",
                        (rs, rowNum) -> new Scored(rs.getLong(1), 1.0 - rs.getDouble(2)),
                        FloatBlobUtil.toBlob(query), k, agentId);
            } catch (Exception e) {
                log.warn("vec0 查询失败，改走暴力查：{}", e.getMessage());
            }
        }
        return bruteForce(memoryDao.impressionsWithEmbedding(agentId), query, k);
    }

    private List<Scored> bruteForce(List<MemoryDao.EmbeddingRow> rows, float[] query, int k) {
        List<Scored> scored = new ArrayList<>();
        for (MemoryDao.EmbeddingRow row : rows) {
            float[] vec = FloatBlobUtil.fromBlob(row.embedding());
            double sim = FloatBlobUtil.cosine(query, vec);
            if (sim >= 0) {
                scored.add(new Scored(row.id(), sim));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return scored.size() > k ? new ArrayList<>(scored.subList(0, k)) : scored;
    }
}