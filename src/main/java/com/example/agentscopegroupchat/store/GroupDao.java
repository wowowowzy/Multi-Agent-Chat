package com.example.agentscopegroupchat.store;

import com.example.agentscopegroupchat.dto.GroupDto;
import com.example.agentscopegroupchat.util.TimeUtil;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 群聊与群成员访问（groups / group_members 表，E1 多群管理）。
 *
 * <p>设计取舍：人设（agents）是**全局共享**的，一个人设可以同时待在多个群里；
 * 群与人是多对多关系，落在 {@code group_members}。消息、群共识、停止开关、记忆进度
 * 都按 {@code group_id} 隔离，所以同一个 agent 在不同群里是"同一个人的不同场合"。
 */
@Repository
public class GroupDao {

    /**
     * 列表查询：群基本信息 + 成员数 / 消息数 / 最后一条消息（左侧会话列表要用的聚合）。
     * 排序按"最近有消息的排前面"，空群沉底（SQLite 里 DESC 时 NULL 排在最后）。
     */
    private static final String SELECT_WITH_AGG =
            "SELECT g.id, g.name, g.avatar, g.notice, g.is_sample, g.created_at, g.updated_at, "
                    + "(SELECT COUNT(*) FROM group_members m JOIN agents a ON a.id = m.agent_id "
                    + "   WHERE m.group_id = g.id AND a.active = 1) AS member_count, "
                    + "(SELECT COUNT(*) FROM messages msg WHERE msg.group_id = g.id) AS message_count, "
                    + "(SELECT content FROM messages msg WHERE msg.group_id = g.id "
                    + "   ORDER BY msg.id DESC LIMIT 1) AS last_message, "
                    + "(SELECT sender_name FROM messages msg WHERE msg.group_id = g.id "
                    + "   ORDER BY msg.id DESC LIMIT 1) AS last_sender, "
                    + "(SELECT created_at FROM messages msg WHERE msg.group_id = g.id "
                    + "   ORDER BY msg.id DESC LIMIT 1) AS last_message_at, "
                    + "COALESCE((SELECT stopped FROM chat_state c WHERE c.group_id = g.id), 0) AS stopped "
                    + "FROM groups g ";

    private static final RowMapper<GroupDto> MAPPER = (rs, rowNum) -> {
        GroupDto g = new GroupDto();
        g.setId(rs.getString("id"));
        g.setName(rs.getString("name"));
        g.setAvatar(rs.getString("avatar"));
        g.setNotice(rs.getString("notice"));
        g.setSample(rs.getInt("is_sample") != 0);
        g.setCreatedAt(rs.getString("created_at"));
        g.setUpdatedAt(rs.getString("updated_at"));
        g.setMemberCount(rs.getInt("member_count"));
        g.setMessageCount(rs.getInt("message_count"));
        g.setLastMessage(rs.getString("last_message"));
        g.setLastSender(rs.getString("last_sender"));
        g.setLastMessageAt(rs.getString("last_message_at"));
        g.setStopped(rs.getInt("stopped") != 0);
        return g;
    };

    private final JdbcTemplate jdbc;

    public GroupDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<GroupDto> findAll() {
        return jdbc.query(SELECT_WITH_AGG + "ORDER BY last_message_at DESC, g.created_at ASC", MAPPER);
    }

    public GroupDto findById(String id) {
        List<GroupDto> list = jdbc.query(SELECT_WITH_AGG + "WHERE g.id = ?", MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public boolean exists(String id) {
        Long v = jdbc.queryForObject("SELECT COUNT(*) FROM groups WHERE id = ?", Long.class, id);
        return v != null && v > 0;
    }

    /** 群名（装配发言 system prompt 用）；群不存在返回 null。 */
    public String name(String id) {
        List<String> list = jdbc.queryForList("SELECT name FROM groups WHERE id = ?", String.class, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<String> allIds() {
        return jdbc.queryForList("SELECT id FROM groups ORDER BY created_at ASC", String.class);
    }

    public void insert(String id, String name, String avatar, String notice, boolean sample) {
        String now = TimeUtil.now();
        jdbc.update("INSERT INTO groups (id, name, avatar, notice, is_sample, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?)",
                id, name, avatar, notice, sample ? 1 : 0, now, now);
    }

    public int updateInfo(String id, String name, String avatar, String notice) {
        return jdbc.update("UPDATE groups SET name = ?, avatar = ?, notice = ?, updated_at = ? WHERE id = ?",
                name, avatar, notice, TimeUtil.now(), id);
    }

    /** 只删群记录本身；消息 / 记忆 / token 由 {@link #deleteGroupData(String)} 处理。 */
    public int delete(String id) {
        return jdbc.update("DELETE FROM groups WHERE id = ?", id);
    }

    /**
     * 解散群聊：清掉这个群的全部派生数据
     * （消息、成员、群共识、记忆进度、开关、token 流水，以及这些消息的决策日志）。
     *
     * <p>决策日志没有 group_id，只能顺着 message_id 反查——否则删了消息会留下一批
     * 永远查不到的孤儿行（决策透视只会按 messageId 查，孤儿行不会被读到，但会一直堆积）。
     */
    public void deleteGroupData(String id) {
        jdbc.update("DELETE FROM group_members WHERE group_id = ?", id);
        jdbc.update("DELETE FROM decision_logs WHERE message_id IN "
                + "(SELECT id FROM messages WHERE group_id = ?)", id);
        jdbc.update("DELETE FROM messages WHERE group_id = ?", id);
        jdbc.update("DELETE FROM shared_facts WHERE group_id = ?", id);
        jdbc.update("DELETE FROM memory_state WHERE group_id = ?", id);
        jdbc.update("DELETE FROM chat_state WHERE group_id = ?", id);
        jdbc.update("DELETE FROM token_usage WHERE group_id = ?", id);
    }

    // ---- 群成员 ----

    public List<String> memberIds(String groupId) {
        return jdbc.queryForList("SELECT agent_id FROM group_members WHERE group_id = ? "
                + "ORDER BY joined_at ASC", String.class, groupId);
    }

    public int memberCount(String groupId) {
        Long v = jdbc.queryForObject("SELECT COUNT(*) FROM group_members WHERE group_id = ?", Long.class, groupId);
        return v == null ? 0 : v.intValue();
    }

    public void addMember(String groupId, String agentId) {
        jdbc.update("INSERT OR IGNORE INTO group_members (group_id, agent_id, joined_at) VALUES (?,?,?)",
                groupId, agentId, TimeUtil.now());
    }

    public void removeMember(String groupId, String agentId) {
        jdbc.update("DELETE FROM group_members WHERE group_id = ? AND agent_id = ?", groupId, agentId);
    }
}