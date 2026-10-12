package com.example.agentscopegroupchat.store;

import com.example.agentscopegroupchat.config.AppProperties;
import com.example.agentscopegroupchat.dto.AgentDef;
import com.example.agentscopegroupchat.util.TimeUtil;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时执行建表脚本（幂等）并在 agents 表为空时种子导入示例人设。
 * DB 为唯一真源，YAML 不存人设。
 */
@Component
public class SchemaInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private final JdbcTemplate jdbc;
    private final AgentDao agentDao;
    private final GroupDao groupDao;
    private final AppProperties props;

    public SchemaInitializer(JdbcTemplate jdbc, AgentDao agentDao, GroupDao groupDao, AppProperties props) {
        this.jdbc = jdbc;
        this.agentDao = agentDao;
        this.groupDao = groupDao;
        this.props = props;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        runSchema();
        migrate();
        seedAgentsIfEmpty();
        ensureSampleGroup();
        ensureChatState();
    }

    /**
     * 旧库补列与回填。
     *
     * <p>{@code schema.sql} 只用 {@code CREATE TABLE IF NOT EXISTS}，对**已经存在**的表不会补列，
     * 所以多群改造新增的列必须在这里显式补齐（SQLite 的 {@code ALTER TABLE ADD COLUMN}）。
     * 全新库在 schema.sql 里就已经有这些列，这里是空操作。
     */
    private void migrate() {
        addColumnIfMissing("token_usage", "group_id", "TEXT");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_token_usage_group ON token_usage (group_id, ts)");
        // 多群之前的历史 token 流水没有群归属，统一回填到示例群
        int backfilled = jdbc.update("UPDATE token_usage SET group_id = ? WHERE group_id IS NULL",
                props.getChat().getGroupId());
        if (backfilled > 0) {
            log.info("回填 {} 行历史 token 流水到示例群 {}（多群改造前无群归属）", backfilled,
                    props.getChat().getGroupId());
        }
    }

    private void addColumnIfMissing(String table, String column, String type) {
        List<String> cols = jdbc.query("PRAGMA table_info(" + table + ")", (rs, rowNum) -> rs.getString("name"));
        if (!cols.contains(column)) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            log.info("旧库补列：{}.{} {}", table, column, type);
        }
    }

    private void runSchema() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/schema.sql");
        StringBuilder sb = new StringBuilder();
        try (InputStream in = resource.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().startsWith("--")) {
                    continue;
                }
                sb.append(line).append('\n');
            }
        }
        for (String stmt : sb.toString().split(";")) {
            String sql = stmt.trim();
            if (!sql.isEmpty()) {
                jdbc.execute(sql);
            }
        }
        log.info("数据库表结构初始化完成");
    }

    private void seedAgentsIfEmpty() {
        if (agentDao.count() > 0) {
            return;
        }
        log.info("agents 表为空，种子导入 5 个示例人设");
        String now = TimeUtil.now();
        for (AgentDef def : seedDefinitions()) {
            def.setCreatedAt(now);
            def.setUpdatedAt(now);
            agentDao.insert(def);
        }
    }

    private void ensureChatState() {
        String groupId = props.getChat().getGroupId();
        ChatStateDao stateDao = new ChatStateDao(jdbc);
        stateDao.ensure(groupId);
    }

    /**
     * 保证示例群存在（E1 多群）。
     *
     * <p>示例群 = {@code app.chat.group-id} / {@code app.chat.group-name} 指向的那个群，
     * 也就是多群改造之前的"唯一那个群"。它被标记为 is_sample（不允许解散），
     * 并且在成员为空时自动把现有启用中的人设全拉进来——这样老库升级上来
     * 的观感与改造前完全一致。
     */
    private void ensureSampleGroup() {
        String groupId = props.getChat().getGroupId();
        String groupName = props.getChat().getGroupName();
        if (!groupDao.exists(groupId)) {
            groupDao.insert(groupId, groupName, "🐟",
                    "示例群：群里的几位都是自带的示例人设，可以直接进来看他们怎么聊。", true);
            log.info("创建示例群「{}」（id={}）", groupName, groupId);
        }
        if (groupDao.memberCount(groupId) == 0) {
            List<AgentDef> agents = agentDao.findActive();
            for (AgentDef a : agents) {
                groupDao.addMember(groupId, a.getId());
            }
            log.info("示例群成员为空，已把 {} 个启用中的人设拉进群", agents.size());
        }
    }

    /** 5 个示例角色：话痨热心大哥 / 高冷毒舌 / 元气少女 / 较真理工男 / 爱发表情包的潜水员。 */
    private List<AgentDef> seedDefinitions() {
        List<AgentDef> list = new ArrayList<>();

        AgentDef a = new AgentDef();
        a.setId("brother");
        a.setName("热心大哥");
        a.setAvatar("😎");
        a.setPersona("你是群里的热心大哥，性格豪爽、爱张罗、什么都懂一点。喜欢主动开话题、给大家出主意、"
                + "照顾冷场。说话带点江湖气和自嘲，偶尔讲人生道理。别人有事你第一个跳出来帮忙。");
        a.setVerbosity(8);
        a.setEmojiTendency(6);
        a.setResponseSpeed(9);
        a.setEagernessBias(9);
        a.setRelationships("{\"毒舌老王\":{\"familiarity\":9,\"affinity\":7,\"meme\":\"互相看不顺眼但离不开\"},"
                + "\"小元气\":{\"familiarity\":8,\"affinity\":9,\"meme\":\"总爱逗她\"}}");
        list.add(a);

        AgentDef b = new AgentDef();
        b.setId("tsundere");
        b.setName("毒舌老王");
        b.setAvatar("😏");
        b.setPersona("你是群里的高冷毒舌担当，说话精简、一针见血、嘴硬心软。不爱附和，喜欢泼冷水和抬杠，"
                + "但关键时刻会认真给出靠谱建议。讨厌长篇大论，回复通常一两句。");
        b.setVerbosity(2);
        b.setEmojiTendency(2);
        b.setResponseSpeed(4);
        b.setEagernessBias(5);
        b.setRelationships("{\"热心大哥\":{\"familiarity\":9,\"affinity\":6,\"meme\":\"嫌他话多\"},"
                + "\"较真张工\":{\"familiarity\":7,\"affinity\":5,\"meme\":\"互相抬杠\"}}");
        list.add(b);

        AgentDef c = new AgentDef();
        c.setId("genki");
        c.setName("小元气");
        c.setAvatar("🌸");
        c.setPersona("你是群里的元气少女，活泼开朗、话多、爱用颜文字和 emoji，情绪起伏大。喜欢分享日常、"
                + "追星、美食和表情包。看到别人不开心会去安慰。");
        c.setVerbosity(7);
        c.setEmojiTendency(10);
        c.setResponseSpeed(10);
        c.setEagernessBias(8);
        c.setRelationships("{\"热心大哥\":{\"familiarity\":8,\"affinity\":9,\"meme\":\"大哥罩着我\"},"
                + "\"潜水员阿呆\":{\"familiarity\":6,\"affinity\":8,\"meme\":\"总想把他炸出来\"}}");
        list.add(c);

        AgentDef d = new AgentDef();
        d.setId("engineer");
        d.setName("较真张工");
        d.setAvatar("🤓");
        d.setPersona("你是群里较真的理工男，逻辑严谨、爱纠正事实错误、喜欢引数据讲原理。说话有条理，"
                + "经常「其实吧……」开头。不擅长接梗，但认真得可爱。");
        d.setVerbosity(6);
        d.setEmojiTendency(1);
        d.setResponseSpeed(6);
        d.setEagernessBias(6);
        d.setRelationships("{\"毒舌老王\":{\"familiarity\":7,\"affinity\":5,\"meme\":\"经常辩论\"},"
                + "\"热心大哥\":{\"familiarity\":7,\"affinity\":7,\"meme\":\"认同他靠谱\"}}");
        list.add(d);

        AgentDef e = new AgentDef();
        e.setId("lurker");
        e.setName("潜水员阿呆");
        e.setAvatar("🐟");
        e.setPersona("你是群里的潜水员，平时不爱说话，但一开口就是发表情包或冷幽默。惜字如金，"
                + "偶尔冒出金句。被 @ 到时才会冒泡。");
        e.setVerbosity(1);
        e.setEmojiTendency(9);
        e.setResponseSpeed(3);
        e.setEagernessBias(2);
        e.setRelationships("{\"小元气\":{\"familiarity\":6,\"affinity\":8,\"meme\":\"被强行 @ 出来\"}}");
        list.add(e);

        return list;
    }
}