package com.example.agentscopegroupchat.store;

import com.example.agentscopegroupchat.dto.ChatStateDto;
import com.example.agentscopegroupchat.util.TimeUtil;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 聊天开关状态访问（chat_state 表，C4）。 */
@Repository
public class ChatStateDao {

    private final JdbcTemplate jdbc;

    public ChatStateDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void ensure(String groupId) {
        jdbc.update("INSERT OR IGNORE INTO chat_state (group_id, stopped, updated_at) VALUES (?, 0, ?)",
                groupId, TimeUtil.now());
    }

    public ChatStateDto get(String groupId) {
        List<ChatStateDto> list = jdbc.query(
                "SELECT group_id, stopped, updated_at FROM chat_state WHERE group_id = ?",
                (rs, rowNum) -> new ChatStateDto(rs.getString("group_id"), rs.getInt("stopped") != 0,
                        rs.getString("updated_at")),
                groupId);
        if (list.isEmpty()) {
            ensure(groupId);
            return new ChatStateDto(groupId, false, TimeUtil.now());
        }
        return list.get(0);
    }

    public boolean isStopped(String groupId) {
        Boolean v = jdbc.query("SELECT stopped FROM chat_state WHERE group_id = ?",
                rs -> rs.next() ? rs.getInt(1) != 0 : null, groupId);
        return v != null && v;
    }

    public void setStopped(String groupId, boolean stopped) {
        ensure(groupId);
        jdbc.update("UPDATE chat_state SET stopped = ?, updated_at = ? WHERE group_id = ?",
                stopped ? 1 : 0, TimeUtil.now(), groupId);
    }
}