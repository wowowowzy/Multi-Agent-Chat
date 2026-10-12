package com.example.agentscopegroupchat.store;

import com.example.agentscopegroupchat.dto.AgentImpression;
import com.example.agentscopegroupchat.dto.SharedFact;
import com.example.agentscopegroupchat.util.TimeUtil;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 长期记忆访问：群共享事实（shared_facts）+ Agent 个人印象（agent_impressions）。 */
@Repository
public class MemoryDao {

    /** 携带向量的记忆行。 */
    public record EmbeddingRow(long id, String text, byte[] embedding) {
    }

    private final JdbcTemplate jdbc;

    public MemoryDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertSharedFact(String groupId, String fact, byte[] embedding) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO shared_facts (group_id, fact, embedding, created_at) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, groupId);
            ps.setString(2, fact);
            ps.setBytes(3, embedding);
            ps.setString(4, TimeUtil.now());
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? -1 : key.longValue();
    }

    public long insertImpression(String agentId, String aboutUser, String impression, byte[] embedding) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO agent_impressions (agent_id, about_user, impression, embedding, created_at) "
                            + "VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, agentId);
            ps.setString(2, aboutUser);
            ps.setString(3, impression);
            ps.setBytes(4, embedding);
            ps.setString(5, TimeUtil.now());
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? -1 : key.longValue();
    }

    public boolean existsSharedFact(String groupId, String fact) {
        Long v = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shared_facts WHERE group_id = ? AND fact = ?", Long.class, groupId, fact);
        return v != null && v > 0;
    }

    public boolean existsImpression(String agentId, String impression) {
        Long v = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_impressions WHERE agent_id = ? AND impression = ?",
                Long.class, agentId, impression);
        return v != null && v > 0;
    }

    public List<EmbeddingRow> sharedFactsWithEmbedding(String groupId) {
        return jdbc.query("SELECT id, fact AS text, embedding FROM shared_facts WHERE group_id = ?",
                (rs, rowNum) -> new EmbeddingRow(rs.getLong("id"), rs.getString("text"), rs.getBytes("embedding")),
                groupId);
    }

    public List<EmbeddingRow> impressionsWithEmbedding(String agentId) {
        return jdbc.query("SELECT id, impression AS text, embedding FROM agent_impressions WHERE agent_id = ?",
                (rs, rowNum) -> new EmbeddingRow(rs.getLong("id"), rs.getString("text"), rs.getBytes("embedding")),
                agentId);
    }

    public List<SharedFact> recentSharedFacts(String groupId, int limit) {
        return jdbc.query("SELECT id, group_id, fact, created_at FROM shared_facts WHERE group_id = ? "
                        + "ORDER BY id DESC LIMIT ?",
                (rs, rowNum) -> {
                    SharedFact f = new SharedFact();
                    f.setId(rs.getLong("id"));
                    f.setGroupId(rs.getString("group_id"));
                    f.setFact(rs.getString("fact"));
                    f.setCreatedAt(rs.getString("created_at"));
                    return f;
                }, groupId, limit);
    }

    public List<AgentImpression> recentImpressions(String agentId, int limit) {
        return jdbc.query("SELECT id, agent_id, about_user, impression, created_at FROM agent_impressions "
                        + "WHERE agent_id = ? ORDER BY id DESC LIMIT ?",
                (rs, rowNum) -> {
                    AgentImpression imp = new AgentImpression();
                    imp.setId(rs.getLong("id"));
                    imp.setAgentId(rs.getString("agent_id"));
                    imp.setAboutUser(rs.getString("about_user"));
                    imp.setImpression(rs.getString("impression"));
                    imp.setCreatedAt(rs.getString("created_at"));
                    return imp;
                }, agentId, limit);
    }

    public String sharedFactText(long id) {
        List<String> list = jdbc.queryForList("SELECT fact FROM shared_facts WHERE id = ?", String.class, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public String impressionText(long id) {
        List<String> list = jdbc.queryForList("SELECT impression FROM agent_impressions WHERE id = ?",
                String.class, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public long countSharedFacts() {
        Long v = jdbc.queryForObject("SELECT COUNT(*) FROM shared_facts", Long.class);
        return v == null ? 0 : v;
    }

    /** 某个群的全部群共识行 id（解散群聊时同步清 vec0 表用）。 */
    public List<Long> sharedFactIds(String groupId) {
        return jdbc.queryForList("SELECT id FROM shared_facts WHERE group_id = ?", Long.class, groupId);
    }

    public long countImpressions() {
        Long v = jdbc.queryForObject("SELECT COUNT(*) FROM agent_impressions", Long.class);
        return v == null ? 0 : v;
    }

    // ---- memory_state ----

    public long getLastExtractMessageId(String groupId) {
        Long v = jdbc.query("SELECT last_extract_message_id FROM memory_state WHERE group_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, groupId);
        return v == null ? 0 : v;
    }

    public void setLastExtractMessageId(String groupId, long messageId) {
        String now = TimeUtil.now();
        jdbc.update("INSERT INTO memory_state (group_id, last_extract_message_id, last_extract_at, updated_at) "
                        + "VALUES (?,?,?,?) ON CONFLICT(group_id) DO UPDATE SET "
                        + "last_extract_message_id = excluded.last_extract_message_id, "
                        + "last_extract_at = excluded.last_extract_at, updated_at = excluded.updated_at",
                groupId, messageId, now, now);
    }

    /** 立即触发（空闲触发用）：只更新触发时间，不改进度。 */
    public void touchLastExtractAt(String groupId) {
        String now = TimeUtil.now();
        jdbc.update("INSERT INTO memory_state (group_id, last_extract_message_id, last_extract_at, updated_at) "
                        + "VALUES (?, 0, ?, ?) ON CONFLICT(group_id) DO UPDATE SET "
                        + "last_extract_at = excluded.last_extract_at, updated_at = excluded.updated_at",
                groupId, now, now);
    }

    public String lastExtractAt(String groupId) {
        return jdbc.query("SELECT last_extract_at FROM memory_state WHERE group_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, groupId);
    }
}