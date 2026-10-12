package com.example.agentscopegroupchat.store;

import com.example.agentscopegroupchat.dto.MessageDto;
import com.example.agentscopegroupchat.util.TimeUtil;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 消息账本访问（messages 表）。头像通过 LEFT JOIN agents 得到。 */
@Repository
public class MessageDao {

    private static final String SELECT_COLS =
            "SELECT m.id, m.group_id, m.sender_id, m.sender_type, m.sender_name, m.content, m.recalled, "
                    + "m.created_at, a.avatar AS avatar FROM messages m "
                    + "LEFT JOIN agents a ON a.id = m.sender_id ";

    private static final RowMapper<MessageDto> MAPPER = (rs, rowNum) -> {
        MessageDto m = new MessageDto();
        m.setId(rs.getLong("id"));
        m.setGroupId(rs.getString("group_id"));
        m.setSenderId(rs.getString("sender_id"));
        m.setSenderType(rs.getString("sender_type"));
        m.setSenderName(rs.getString("sender_name"));
        m.setContent(rs.getString("content"));
        m.setRecalled(rs.getInt("recalled") != 0);
        m.setCreatedAt(rs.getString("created_at"));
        m.setAvatar(rs.getString("avatar"));
        return m;
    };

    private final JdbcTemplate jdbc;

    public MessageDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(MessageDto m) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO messages (group_id, sender_id, sender_type, sender_name, content, recalled, created_at) "
                            + "VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, m.getGroupId());
            ps.setString(2, m.getSenderId());
            ps.setString(3, m.getSenderType());
            ps.setString(4, m.getSenderName());
            ps.setString(5, m.getContent());
            ps.setInt(6, m.isRecalled() ? 1 : 0);
            ps.setString(7, m.getCreatedAt() == null ? TimeUtil.now() : m.getCreatedAt());
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? -1 : key.longValue();
    }

    public MessageDto findById(long id) {
        List<MessageDto> list = jdbc.query(SELECT_COLS + "WHERE m.id = ?", MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 最近 limit 条，按时间升序返回。 */
    public List<MessageDto> findRecent(String groupId, int limit) {
        List<MessageDto> list = jdbc.query(
                SELECT_COLS + "WHERE m.group_id = ? ORDER BY m.id DESC LIMIT ?", MAPPER, groupId, limit);
        Collections.reverse(list);
        return list;
    }

    /** 历史分页：加载 id 小于 beforeId 的 limit 条（升序返回）。 */
    public List<MessageDto> findBefore(String groupId, long beforeId, int limit) {
        List<MessageDto> list = jdbc.query(
                SELECT_COLS + "WHERE m.group_id = ? AND m.id < ? ORDER BY m.id DESC LIMIT ?",
                MAPPER, groupId, beforeId, limit);
        Collections.reverse(list);
        return list;
    }

    /** 取 id 大于 afterId 的消息（升序），用于记忆提取。 */
    public List<MessageDto> findAfter(String groupId, long afterId, int limit) {
        return new ArrayList<>(jdbc.query(
                SELECT_COLS + "WHERE m.group_id = ? AND m.id > ? ORDER BY m.id ASC LIMIT ?",
                MAPPER, groupId, afterId, limit));
    }

    public void recall(long id) {
        jdbc.update("UPDATE messages SET recalled = 1 WHERE id = ?", id);
    }

    public String lastMessageTime(String groupId) {
        List<String> list = jdbc.queryForList(
                "SELECT created_at FROM messages WHERE group_id = ? ORDER BY id DESC LIMIT 1",
                String.class, groupId);
        return list.isEmpty() ? null : list.get(0);
    }

    public long maxId(String groupId) {
        Long v = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM messages WHERE group_id = ?", Long.class, groupId);
        return v == null ? 0 : v;
    }

    public long countSince(String groupId, long afterId) {
        Long v = jdbc.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE group_id = ? AND id > ?", Long.class, groupId, afterId);
        return v == null ? 0 : v;
    }
}