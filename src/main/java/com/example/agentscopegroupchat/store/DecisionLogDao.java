package com.example.agentscopegroupchat.store;

import com.example.agentscopegroupchat.dto.DecisionDto;
import com.example.agentscopegroupchat.util.TimeUtil;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 决策透视访问（decision_logs 表，D1）。 */
@Repository
public class DecisionLogDao {

    private static final RowMapper<DecisionDto> MAPPER = (rs, rowNum) -> {
        DecisionDto d = new DecisionDto();
        d.setId(rs.getLong("id"));
        d.setMessageId(rs.getLong("message_id"));
        d.setAgentId(rs.getString("agent_id"));
        d.setAgentName(rs.getString("agent_name"));
        d.setAvatar(rs.getString("avatar"));
        d.setWantToSpeak(rs.getInt("want_to_speak") != 0);
        d.setEagerness(rs.getInt("eagerness"));
        d.setGist(rs.getString("gist"));
        d.setOutcome(rs.getString("outcome"));
        d.setReason(rs.getString("reason"));
        d.setCreatedAt(rs.getString("created_at"));
        return d;
    };

    private final JdbcTemplate jdbc;

    public DecisionLogDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(DecisionDto d) {
        jdbc.update("INSERT INTO decision_logs (message_id, agent_id, want_to_speak, eagerness, gist, outcome, reason, "
                        + "created_at) VALUES (?,?,?,?,?,?,?,?)",
                d.getMessageId(), d.getAgentId(), d.isWantToSpeak() ? 1 : 0, d.getEagerness(), d.getGist(),
                d.getOutcome(), d.getReason(), d.getCreatedAt() == null ? TimeUtil.now() : d.getCreatedAt());
    }

    public List<DecisionDto> findByMessageId(long messageId) {
        return jdbc.query("SELECT d.*, a.name AS agent_name, a.avatar AS avatar FROM decision_logs d "
                        + "LEFT JOIN agents a ON a.id = d.agent_id WHERE d.message_id = ? "
                        + "ORDER BY d.eagerness DESC, d.id ASC",
                MAPPER, messageId);
    }
}