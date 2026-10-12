package com.example.agentscopegroupchat.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.agentscopegroupchat.dto.AgentDef;
import com.example.agentscopegroupchat.dto.MessageDto;
import com.example.agentscopegroupchat.util.TimeUtil;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** 人设 → prompt 组装单测，重点覆盖群内关系的中文化渲染。 */
class PromptsTest {

    /** 固定注入的"当前时间"，让断言与真实时钟无关。 */
    private static final String NOW = "2026年10月12日 星期一 14:43（下午，工作日）";

    private static AgentDef agent(String relationships) {
        AgentDef a = new AgentDef();
        a.setId("genki");
        a.setName("小元气");
        a.setPersona("活泼爱起哄");
        a.setRelationships(relationships);
        return a;
    }

    /** 关系 JSON 应翻成中文标签，不能把英文字段名和 Map.toString() 的形状喂给模型。 */
    @Test
    void relationshipsAreRenderedInChinese() {
        String p = Prompts.systemPrompt(
                agent("{\"毒舌老王\":{\"familiarity\":9,\"affinity\":7,\"meme\":\"互相看不顺眼\"}}"), "摸鱼搭子群", NOW);
        assertTrue(p.contains("【你在群里的关系】"), p);
        assertTrue(p.contains("与「毒舌老王」"), p);
        assertTrue(p.contains("熟悉度 9/10"), p);
        assertTrue(p.contains("好感 7/10"), p);
        assertTrue(p.contains("内部梗/态度：互相看不顺眼"), p);
        // 英文字段名与 Java Map 的花括号不应出现在 prompt 里
        assertFalse(p.contains("familiarity"), p);
        assertFalse(p.contains("affinity"), p);
        assertFalse(p.contains("{"), p);
    }

    /** 未知字段要原样保留，避免用户自定义维度被静默丢掉。 */
    @Test
    void unknownRelationshipKeysAreKept() {
        String p = Prompts.systemPrompt(agent("{\"老王\":{\"熟悉程度\":\"很铁\"}}"), "群", NOW);
        assertTrue(p.contains("熟悉程度：很铁"), p);
    }

    /** 值不是对象时（例如 {"老王":"关系一般"}）也要能渲染，不丢信息。 */
    @Test
    void nonObjectRelationshipValueIsKept() {
        String p = Prompts.systemPrompt(agent("{\"老王\":\"关系一般\"}"), "群", NOW);
        assertTrue(p.contains("与「老王」：关系一般"), p);
    }

    /** 非 JSON 内容（管理页高级模式写的中文）原样进 prompt。 */
    @Test
    void plainTextRelationshipIsPassedThrough() {
        String p = Prompts.systemPrompt(agent("和小元气很熟，总爱逗她"), "群", NOW);
        assertTrue(p.contains("和小元气很熟，总爱逗她"), p);
    }

    /** 留空时不应出现"你在群里的关系"小节。 */
    @Test
    void blankRelationshipsOmitSection() {
        assertFalse(Prompts.systemPrompt(agent(null), "群", NOW).contains("你在群里的关系"));
        assertFalse(Prompts.systemPrompt(agent("   "), "群", NOW).contains("你在群里的关系"));
    }

    /** 关系里的空属性值不应渲染成 "熟悉度 /10"。 */
    @Test
    void blankFieldValuesAreSkipped() {
        String p = Prompts.systemPrompt(agent("{\"老王\":{\"familiarity\":\"\",\"affinity\":5}}"), "群", NOW);
        assertFalse(p.contains("熟悉度"), p);
        assertTrue(p.contains("好感 5/10"), p);
    }

    /** 门控提示里同样走中文化渲染。 */
    @Test
    void gatePromptAlsoRendersRelationships() {
        String p = Prompts.gateUserPrompt(agent("{\"老王\":{\"familiarity\":8}}"), java.util.List.of(), null, false, null, NOW);
        assertTrue(p.contains("与「老王」：熟悉度 8/10"), p);
        assertEquals(true, p.contains("只输出这段 JSON"));
    }

    /** 每个 prompt 都要带"当前时间"，否则模型会臆测"大晚上的"。 */
    @Test
    void everyPromptCarriesCurrentTime() {
        String sys = Prompts.systemPrompt(agent(null), "群", TimeUtil.nowContext());
        assertTrue(sys.contains("【当前时间】"), sys);
        assertTrue(sys.contains("星期"), sys);
        assertTrue(sys.contains("工作日") || sys.contains("周末") || sys.contains("假期") || sys.contains("调休"), sys);
        assertTrue(sys.contains("以【当前时间】为准"), sys);

        String gate = Prompts.gateUserPrompt(agent(null), java.util.List.of(), null, false, null, NOW);
        assertTrue(gate.contains("【当前时间】"), gate);

        String speak = Prompts.speakUserPrompt(agent(null), java.util.List.of(), null, null, NOW);
        assertTrue(speak.contains("【当前时间】"), speak);

        String idle = Prompts.idleTopicUserPrompt(agent(null), java.util.List.of(), NOW);
        assertTrue(idle.contains("【当前时间】"), idle);
    }

    /** 聊天记录只在跨天 / 断档 >30 分钟处插时间标签，避免每条都带时间戳浪费 token。 */
    @Test
    void transcriptInsertsTimeStampsOnlyAtGaps() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime old = now.minusDays(2).withHour(9).withMinute(20).withSecond(0).withNano(0);
        MessageDto m1 = msg("热心大哥", "早", old);
        MessageDto m2 = msg("小元气", "早呀", old.plusMinutes(2));      // 2 分钟后 → 不插标签
        MessageDto m3 = msg("你", "现在聊点啥", now.withHour(15).withMinute(0).withSecond(0).withNano(0));
        String t = Prompts.transcript(java.util.List.of(m1, m2, m3));
        assertTrue(t.contains("09:20"), t);        // 两条前那天有标签
        assertFalse(t.contains("09:22"), t);       // 紧接着的一条不再插
        assertTrue(t.contains("[今天 15:00]"), t); // 跨天 → 插"今天"
    }

    @Test
    void emptyTranscriptIsBlank() {
        assertEquals("", Prompts.transcript(java.util.List.of()));
        assertEquals("", Prompts.transcript(null));
    }

    private static MessageDto msg(String sender, String content, LocalDateTime at) {
        MessageDto m = new MessageDto();
        m.setSenderName(sender);
        m.setSenderType(MessageDto.TYPE_AGENT);
        m.setContent(content);
        m.setCreatedAt(at.format(TimeUtil.FMT));
        return m;
    }
}