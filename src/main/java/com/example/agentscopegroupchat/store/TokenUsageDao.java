package com.example.agentscopegroupchat.store;

import com.example.agentscopegroupchat.dto.TokenStatsDto;
import com.example.agentscopegroupchat.dto.TokenUsageDto;
import com.example.agentscopegroupchat.util.TimeUtil;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Token 流水与统计访问（token_usage 表）。
 *
 * <p>E1 多群改造后每行流水都带 {@code group_id}，因此统计有两种视角：
 * 不带 groupId = 全部群聊总览（并给出按群聚合）；带 groupId = 单个群内部的按人 / 按用途明细。
 */
@Repository
public class TokenUsageDao {

    /** 无归属 agent 的调用（记忆提取 / embedding）在统计里的显示名。 */
    private static final String PURPOSE_LESS_OWNER = "系统（记忆 / 向量）";

    private final JdbcTemplate jdbc;

    public TokenUsageDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TokenUsageDto u) {
        jdbc.update("INSERT INTO token_usage (ts, group_id, agent_id, purpose, input_tokens, output_tokens) "
                        + "VALUES (?,?,?,?,?,?)",
                u.getTs() == null ? TimeUtil.now() : u.getTs(),
                u.getGroupId(), u.getAgentId(), u.getPurpose(), u.getInputTokens(), u.getOutputTokens());
    }

    public void insert(String groupId, String agentId, String purpose, int inputTokens, int outputTokens) {
        TokenUsageDto u = new TokenUsageDto();
        u.setGroupId(groupId);
        u.setAgentId(agentId);
        u.setPurpose(purpose);
        u.setInputTokens(inputTokens);
        u.setOutputTokens(outputTokens);
        insert(u);
    }

    /**
     * 统计聚合。
     *
     * @param groupId 为空 = 全部群聊总览（含 byGroup）；否则 = 该群内部明细（含 byAgent）
     */
    public TokenStatsDto stats(String groupId) {
        TokenStatsDto dto = new TokenStatsDto();
        boolean scoped = StringUtils.hasText(groupId);
        String where = scoped ? " WHERE group_id = ?" : "";
        Object[] args = scoped ? new Object[] {groupId} : new Object[0];

        dto.setScopeGroupId(scoped ? groupId : null);

        jdbc.query("SELECT COALESCE(SUM(input_tokens),0) AS i, COALESCE(SUM(output_tokens),0) AS o "
                + "FROM token_usage" + where, rs -> {
                    dto.setTotalInput(rs.getLong("i"));
                    dto.setTotalOutput(rs.getLong("o"));
                }, args);
        dto.setTotalTokens(dto.getTotalInput() + dto.getTotalOutput());

        String todayStart = TimeUtil.todayStart();
        String todayWhere = scoped ? " WHERE group_id = ? AND ts >= ?" : " WHERE ts >= ?";
        Object[] todayArgs = scoped ? new Object[] {groupId, todayStart} : new Object[] {todayStart};
        jdbc.query("SELECT COALESCE(SUM(input_tokens),0) AS i, COALESCE(SUM(output_tokens),0) AS o "
                + "FROM token_usage" + todayWhere, rs -> {
                    dto.setTodayInput(rs.getLong("i"));
                    dto.setTodayOutput(rs.getLong("o"));
                }, todayArgs);
        dto.setTodayTokens(dto.getTodayInput() + dto.getTodayOutput());

        if (!scoped) {
            // 总览：按群聚合（LEFT JOIN 保证群被改名/删除后流水仍能归集）
            dto.setByGroup(jdbc.query(
                    "SELECT t.group_id AS k, g.name AS n, "
                            + "COALESCE(SUM(t.input_tokens),0) AS i, COALESCE(SUM(t.output_tokens),0) AS o "
                            + "FROM token_usage t LEFT JOIN groups g ON g.id = t.group_id "
                            + "GROUP BY t.group_id, g.name ORDER BY (i+o) DESC",
                    (rs, rowNum) -> {
                        String key = rs.getString("k");
                        String name = rs.getString("n");
                        return new TokenStatsDto.Agg(key == null ? "-" : key,
                                name == null ? "（已解散 / 无归属）" : name, rs.getLong("i"), rs.getLong("o"));
                    }));
        } else {
            // 群内明细：按 agent
            dto.setByAgent(jdbc.query(
                    "SELECT t.agent_id AS k, a.name AS n, "
                            + "COALESCE(SUM(t.input_tokens),0) AS i, COALESCE(SUM(t.output_tokens),0) AS o "
                            + "FROM token_usage t LEFT JOIN agents a ON a.id = t.agent_id "
                            + "WHERE t.group_id = ? "
                            + "GROUP BY t.agent_id, a.name ORDER BY (i+o) DESC",
                    (rs, rowNum) -> {
                        String key = rs.getString("k");
                        String name = rs.getString("n");
                        if (name == null) {
                            // agent_id 为空 → 记忆提取 / embedding 这类"不属于某个成员"的调用
                            name = key == null ? PURPOSE_LESS_OWNER : key;
                            key = key == null ? "-" : key;
                        }
                        return new TokenStatsDto.Agg(key, name, rs.getLong("i"), rs.getLong("o"));
                    }, groupId));
            String gname = jdbc.query("SELECT name FROM groups WHERE id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, groupId);
            dto.setScopeGroupName(gname == null ? groupId : gname);
        }

        dto.setByPurpose(jdbc.query(
                "SELECT purpose AS k, COALESCE(SUM(input_tokens),0) AS i, COALESCE(SUM(output_tokens),0) AS o "
                        + "FROM token_usage" + where + " GROUP BY purpose ORDER BY (i+o) DESC",
                (rs, rowNum) -> new TokenStatsDto.Agg(rs.getString("k"), purposeLabel(rs.getString("k")),
                        rs.getLong("i"), rs.getLong("o")),
                args));
        return dto;
    }

    /** 全部群聊累计（SSE 页脚兜底用）。 */
    public long totalTokens() {
        return totalTokens(null);
    }

    /** 指定群累计 token（群聊页脚显示"本群 tokens"）。 */
    public long totalTokens(String groupId) {
        Long v;
        if (groupId == null) {
            v = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(input_tokens + output_tokens),0) FROM token_usage", Long.class);
        } else {
            v = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(input_tokens + output_tokens),0) FROM token_usage WHERE group_id = ?",
                    Long.class, groupId);
        }
        return v == null ? 0 : v;
    }

    private static String purposeLabel(String purpose) {
        if (purpose == null) {
            return "-";
        }
        return switch (purpose) {
            case TokenUsageDto.PURPOSE_GATE -> "门控 GATE";
            case TokenUsageDto.PURPOSE_SPEAK -> "发言 SPEAK";
            case TokenUsageDto.PURPOSE_MEMORY -> "记忆 MEMORY";
            case TokenUsageDto.PURPOSE_EMBED -> "向量 EMBED";
            default -> purpose;
        };
    }
}