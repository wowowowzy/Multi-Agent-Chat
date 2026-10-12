package com.example.agentscopegroupchat.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.agentscopegroupchat.config.AppProperties;
import com.example.agentscopegroupchat.config.SqliteDataSource;
import com.example.agentscopegroupchat.dto.DecisionDto;
import com.example.agentscopegroupchat.dto.GroupDto;
import com.example.agentscopegroupchat.dto.MessageDto;
import com.example.agentscopegroupchat.dto.TokenStatsDto;
import com.example.agentscopegroupchat.dto.TokenUsageDto;
import com.example.agentscopegroupchat.util.TimeUtil;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 多群（E1）数据层测试：跑真实的 {@code db/schema.sql}，验证示例群、群成员隔离、
 * 按群统计与解散群聊的数据清理；另外验证"旧库升级"时会补列并回填历史 token 流水。
 *
 * <p>用临时文件库而非内存库，是为了跟生产路径完全一致（SqliteDataSource + 同一个 schema.sql）。
 */
class MultiGroupSchemaTest {

    private static final String SAMPLE = "default";

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private AgentDao agentDao;
    private GroupDao groupDao;
    private MessageDao messageDao;
    private MemoryDao memoryDao;
    private TokenUsageDao tokenUsageDao;
    private ChatStateDao chatStateDao;
    private AppProperties props;

    @BeforeEach
    void setUp() throws Exception {
        SqliteDataSource ds = new SqliteDataSource(tempDir.resolve("multi.db").toString(), null);
        jdbc = new JdbcTemplate(ds);
        agentDao = new AgentDao(jdbc);
        groupDao = new GroupDao(jdbc);
        messageDao = new MessageDao(jdbc);
        memoryDao = new MemoryDao(jdbc);
        tokenUsageDao = new TokenUsageDao(jdbc);
        chatStateDao = new ChatStateDao(jdbc);
        props = new AppProperties();
        new SchemaInitializer(jdbc, agentDao, groupDao, props).afterPropertiesSet();
    }

    @Test
    void freshDbCreatesSampleGroupContainingAllSeededPersonas() {
        assertTrue(groupDao.exists(SAMPLE), "示例群应被自动创建");
        GroupDto sample = groupDao.findById(SAMPLE);
        assertTrue(sample.isSample(), "示例群应带 is_sample 标记（不允许解散）");
        assertEquals("摸鱼搭子群", sample.getName());

        // 5 个种子人设都自动进了示例群：升级到多群后观感与改造前一致
        assertEquals(5, groupDao.memberCount(SAMPLE));
        assertEquals(5, agentDao.findByGroup(SAMPLE).size());
        assertEquals(5, agentDao.findActive().size());
    }

    @Test
    void joiningAnotherGroupDoesNotDuplicatePersonas() {
        groupDao.insert("g-test", "AI 摸鱼局", "💬", null, false);
        groupDao.addMember("g-test", "brother");
        groupDao.addMember("g-test", "genki");

        // 同一个 agent 同时待在两个群里，但 agents 表里只有一份人设
        assertEquals(2, agentDao.findByGroup("g-test").size());
        assertEquals(5, agentDao.findByGroup(SAMPLE).size());
        assertEquals(5, agentDao.findActive().size());

        // 重复插入同一个成员是幂等的
        groupDao.addMember("g-test", "brother");
        assertEquals(2, groupDao.memberCount("g-test"));

        // 移出群只影响这一个群
        groupDao.removeMember("g-test", "brother");
        assertEquals(1, groupDao.memberCount("g-test"));
        assertEquals(5, agentDao.findByGroup(SAMPLE).size());
    }

    @Test
    void messagesAreScopedPerGroup() {
        groupDao.insert("g-test", "AI 摸鱼局", "💬", null, false);
        insertMessage(SAMPLE, "我", MessageDto.TYPE_HUMAN, "示例群说一句");
        insertMessage("g-test", "我", MessageDto.TYPE_HUMAN, "另一个群说一句");

        assertEquals(1, messageDao.findRecent(SAMPLE, 10).size());
        List<MessageDto> g = messageDao.findRecent("g-test", 10);
        assertEquals(1, g.size());
        assertEquals("另一个群说一句", g.get(0).getContent());

        // 群列表的预览字段取的是各自群内最后一条消息
        assertEquals(1, groupDao.findById(SAMPLE).getMessageCount());
        assertEquals("另一个群说一句", groupDao.findById("g-test").getLastMessage());
    }

    @Test
    void tokenStatsCanBeViewedOverallAndPerGroup() {
        groupDao.insert("g-test", "AI 摸鱼局", "💬", null, false);
        tokenUsageDao.insert(SAMPLE, "brother", TokenUsageDto.PURPOSE_SPEAK, 100, 20);
        tokenUsageDao.insert("g-test", "genki", TokenUsageDto.PURPOSE_SPEAK, 50, 10);
        // 记忆 / 向量这类调用没有归属 agent，但归属群
        tokenUsageDao.insert("g-test", null, TokenUsageDto.PURPOSE_EMBED, 7, 0);

        TokenStatsDto all = tokenUsageDao.stats(null);
        assertEquals(187, all.getTotalTokens());
        assertEquals(2, all.getByGroup().size(), "总览应给出按群聚合");
        assertTrue(all.getByGroup().stream().anyMatch(a -> "AI 摸鱼局".equals(a.getName())));
        assertEquals(0, all.getByAgent().size(), "总览视图不展开成员明细");

        TokenStatsDto scoped = tokenUsageDao.stats("g-test");
        assertEquals("AI 摸鱼局", scoped.getScopeGroupName());
        assertEquals(67, scoped.getTotalTokens());
        assertEquals(0, scoped.getByGroup().size(), "群内视图不再重复给按群聚合");
        assertEquals(2, scoped.getByAgent().size(), "群内应能按成员拆开");
        // 无归属 agent 的调用有中文兜底名
        assertTrue(scoped.getByAgent().stream()
                .anyMatch(a -> "系统（记忆 / 向量）".equals(a.getName())));

        assertEquals(187, tokenUsageDao.totalTokens());
        assertEquals(67, tokenUsageDao.totalTokens("g-test"));
    }

    @Test
    void dissolvingAGroupClearsOnlyItsOwnData() {
        groupDao.insert("g-test", "AI 摸鱼局", "💬", null, false);
        groupDao.addMember("g-test", "genki");
        chatStateDao.ensure("g-test");
        chatStateDao.setStopped("g-test", true);
        long msgId = insertMessage("g-test", "我", MessageDto.TYPE_HUMAN, "会被删掉");
        insertMessage(SAMPLE, "我", MessageDto.TYPE_HUMAN, "示例群这条要留着");
        memoryDao.insertSharedFact("g-test", "这个群里只聊摸鱼", null);
        memoryDao.setLastExtractMessageId("g-test", 12L);
        tokenUsageDao.insert("g-test", "genki", TokenUsageDto.PURPOSE_SPEAK, 30, 5);
        // 决策日志只有 message_id、没有 group_id，解散时要顺着 message_id 一起清掉，否则留孤儿行
        DecisionLogDao decisionLogDao = new DecisionLogDao(jdbc);
        DecisionDto d = new DecisionDto();
        d.setMessageId(msgId);
        d.setAgentId("genki");
        d.setWantToSpeak(true);
        d.setEagerness(7);
        d.setOutcome("SELECTED");
        d.setReason("想接话");
        decisionLogDao.insert(d);
        assertEquals(1, decisionLogDao.findByMessageId(msgId).size());

        groupDao.deleteGroupData("g-test");
        groupDao.delete("g-test");

        assertFalse(groupDao.exists("g-test"), "群记录应被删除");
        assertEquals(0, messageDao.findRecent("g-test", 10).size());
        assertEquals(0, messageDao.countSince("g-test", 0));
        assertEquals(0, memoryDao.countSharedFacts());
        assertTrue(memoryDao.sharedFactIds("g-test").isEmpty());
        assertEquals(0, tokenUsageDao.totalTokens("g-test"));
        assertFalse(chatStateDao.isStopped("g-test"));
        assertTrue(decisionLogDao.findByMessageId(msgId).isEmpty(), "决策日志不应留孤儿行");

        // 示例群毫发无损
        assertEquals(5, agentDao.findByGroup(SAMPLE).size());
        assertEquals(1, messageDao.findRecent(SAMPLE, 10).size());
        assertEquals(0, memoryDao.getLastExtractMessageId("g-test"));
    }

    /**
     * 旧库升级：多群之前的 token_usage 没有 group_id 列，启动时要补列并把历史流水
     * 回填到示例群，否则统计页会出现"无归属"的孤儿行。
     */
    @Test
    void legacyDbGetsTokenGroupColumnBackfilledToSampleGroup() throws Exception {
        SqliteDataSource ds = new SqliteDataSource(tempDir.resolve("legacy.db").toString(), null);
        JdbcTemplate old = new JdbcTemplate(ds);
        // 模拟"改造前"的表结构（没有 group_id）
        old.execute("CREATE TABLE token_usage (id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT NOT NULL, "
                + "agent_id TEXT, purpose TEXT NOT NULL, input_tokens INTEGER NOT NULL DEFAULT 0, "
                + "output_tokens INTEGER NOT NULL DEFAULT 0)");
        old.execute("INSERT INTO token_usage (ts, agent_id, purpose, input_tokens, output_tokens) "
                + "VALUES ('2026-01-01 00:00:00','brother','GATE',10,5)");

        JdbcTemplate jdbc2 = new JdbcTemplate(ds);
        AgentDao agentDao2 = new AgentDao(jdbc2);
        GroupDao groupDao2 = new GroupDao(jdbc2);
        AppProperties props2 = new AppProperties();
        new SchemaInitializer(jdbc2, agentDao2, groupDao2, props2).afterPropertiesSet();

        assertEquals(SAMPLE, jdbc2.queryForObject("SELECT group_id FROM token_usage", String.class));
        assertEquals(15, new TokenUsageDao(jdbc2).totalTokens(SAMPLE));
        // 同一个库再跑一次初始化不应出错（幂等）
        new SchemaInitializer(jdbc2, agentDao2, groupDao2, props2).afterPropertiesSet();
        assertEquals(15, new TokenUsageDao(jdbc2).totalTokens(SAMPLE));
        assertEquals(5, groupDao2.memberCount(SAMPLE));
    }

    private long insertMessage(String groupId, String senderName, String type, String content) {
        MessageDto m = new MessageDto();
        m.setGroupId(groupId);
        m.setSenderId("human");
        m.setSenderType(type);
        m.setSenderName(senderName);
        m.setContent(content);
        m.setCreatedAt(TimeUtil.now());
        return messageDao.insert(m);
    }
}