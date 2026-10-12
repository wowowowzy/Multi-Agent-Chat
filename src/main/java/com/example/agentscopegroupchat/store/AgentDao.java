package com.example.agentscopegroupchat.store;

import com.example.agentscopegroupchat.dto.AgentDef;
import com.example.agentscopegroupchat.util.TimeUtil;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 人设卡访问（agents 表）。DB 为唯一真源。 */
@Repository
public class AgentDao {

    private static final String SELECT_COLS =
            "SELECT id, name, avatar, persona, verbosity, emoji_tendency, response_speed, eagerness_bias, "
                    + "relationships, active, created_at, updated_at FROM agents ";

    private static final RowMapper<AgentDef> MAPPER = (rs, rowNum) -> {
        AgentDef a = new AgentDef();
        a.setId(rs.getString("id"));
        a.setName(rs.getString("name"));
        a.setAvatar(rs.getString("avatar"));
        a.setPersona(rs.getString("persona"));
        a.setVerbosity(rs.getInt("verbosity"));
        a.setEmojiTendency(rs.getInt("emoji_tendency"));
        a.setResponseSpeed(rs.getInt("response_speed"));
        a.setEagernessBias(rs.getInt("eagerness_bias"));
        a.setRelationships(rs.getString("relationships"));
        a.setActive(rs.getInt("active") != 0);
        a.setCreatedAt(rs.getString("created_at"));
        a.setUpdatedAt(rs.getString("updated_at"));
        return a;
    };

    private final JdbcTemplate jdbc;

    public AgentDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AgentDef> findAll() {
        return jdbc.query(SELECT_COLS + "ORDER BY created_at ASC", MAPPER);
    }

    public List<AgentDef> findActive() {
        return jdbc.query(SELECT_COLS + "WHERE active = 1 ORDER BY created_at ASC", MAPPER);
    }

    /**
     * 某个群的启用中成员（E1 多群）。群成员关系在 group_members，人设本体仍在 agents，
     * 所以"同一个人设可以同时在多个群里"。
     */
    public List<AgentDef> findByGroup(String groupId) {
        return jdbc.query(SELECT_COLS + "JOIN group_members m ON m.agent_id = agents.id "
                + "WHERE m.group_id = ? AND active = 1 ORDER BY m.joined_at ASC, agents.created_at ASC",
                MAPPER, groupId);
    }

    /** 批量按 id 取人设（顺序不保证）。 */
    public List<AgentDef> findByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbc.query(SELECT_COLS + "WHERE id IN (" + placeholders + ") ORDER BY created_at ASC",
                MAPPER, ids.toArray());
    }

    public AgentDef findById(String id) {
        List<AgentDef> list = jdbc.query(SELECT_COLS + "WHERE id = ?", MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public long count() {
        Long v = jdbc.queryForObject("SELECT COUNT(*) FROM agents", Long.class);
        return v == null ? 0 : v;
    }

    public void insert(AgentDef a) {
        String now = TimeUtil.now();
        jdbc.update("INSERT INTO agents (id, name, avatar, persona, verbosity, emoji_tendency, response_speed, "
                        + "eagerness_bias, relationships, active, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                a.getId(), a.getName(), a.getAvatar(), a.getPersona(), a.getVerbosity(), a.getEmojiTendency(),
                a.getResponseSpeed(), a.getEagernessBias(), a.getRelationships(), a.isActive() ? 1 : 0,
                a.getCreatedAt() == null ? now : a.getCreatedAt(),
                a.getUpdatedAt() == null ? now : a.getUpdatedAt());
    }

    public int update(AgentDef a) {
        return jdbc.update("UPDATE agents SET name=?, avatar=?, persona=?, verbosity=?, emoji_tendency=?, "
                        + "response_speed=?, eagerness_bias=?, relationships=?, active=?, updated_at=? WHERE id=?",
                a.getName(), a.getAvatar(), a.getPersona(), a.getVerbosity(), a.getEmojiTendency(),
                a.getResponseSpeed(), a.getEagernessBias(), a.getRelationships(), a.isActive() ? 1 : 0,
                TimeUtil.now(), a.getId());
    }

    /** 逻辑删除（停用）。历史消息与个人印象保留。 */
    public int deactivate(String id) {
        return jdbc.update("UPDATE agents SET active = 0, updated_at = ? WHERE id = ?", TimeUtil.now(), id);
    }
}