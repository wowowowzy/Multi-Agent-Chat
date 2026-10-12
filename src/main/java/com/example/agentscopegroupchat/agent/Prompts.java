package com.example.agentscopegroupchat.agent;

import com.example.agentscopegroupchat.dto.AgentDef;
import com.example.agentscopegroupchat.dto.MessageDto;
import com.example.agentscopegroupchat.util.TimeUtil;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Prompt 组装：人设 → 系统提示；门控 / 发言 → 用户提示。 */
public final class Prompts {

    /** 门控 agent 的系统提示（通用，具体身份在用户消息里给出）。 */
    public static final String GATE_SYS = """
            你在模拟微信群成员的真实反应。根据给定成员的人设与最近的聊天记录，
            判断"这个人此刻是否想插话"。要克制、真实：真实群聊里大多数消息只有少数人回应，
            沉默是常态，不要人人都抢着说。判断要贴合该成员的性格与表达欲，并给出想说的话的一句话概括。
            你只输出 JSON，不要输出任何解释、前后缀或 Markdown 代码块。
            """;

    /** 门控结果 JSON 样例（写进 prompt，指导模型输出合法 JSON）。 */
    private static final String GATE_JSON_SAMPLE =
            "{\"wantToSpeak\": true, \"eagerness\": 7, \"gist\": \"一句话概括你想说的话\"}";

    private Prompts() {
    }

    /** 把"当前时间"块写进 prompt：模型不知道真实时间就会臆测"大晚上的"。 */
    private static void appendTime(StringBuilder sb, String nowLine) {
        String line = nowLine == null || nowLine.isBlank() ? TimeUtil.nowContext() : nowLine.strip();
        sb.append("【当前时间】").append(line).append('\n');
    }

    /** 聊天 agent 的系统提示：人设 + 性格参数 + 群内关系 + 发言要求。 */
    public static String systemPrompt(AgentDef agent, String groupName, String nowLine) {
        return systemPrompt(agent, groupName, null, nowLine);
    }

    /** 带群公告的版本（多群：每个群有自己的名字与公告）。 */
    public static String systemPrompt(AgentDef agent, String groupName, String groupNotice, String nowLine) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是微信群「").append(nvl(groupName)).append("」里的一名成员，群昵称叫「")
                .append(agent.getName()).append("」。\n");
        if (groupNotice != null && !groupNotice.isBlank()) {
            sb.append("【群公告】\n").append(groupNotice.strip()).append('\n');
        }
        appendTime(sb, nowLine);
        sb.append("【你的人设】\n").append(nvl(agent.getPersona())).append('\n');
        sb.append("【性格参数】\n")
                .append("啰嗦程度 ").append(agent.getVerbosity()).append("/10（越大越爱长篇大论，越小越惜字如金）；")
                .append("emoji 倾向 ").append(agent.getEmojiTendency()).append("/10；")
                .append("回应速度 ").append(agent.getResponseSpeed()).append("/10（越大回复越快）；")
                .append("表达欲基准 ").append(agent.getEagernessBias()).append("/10。\n");
        String relations = renderRelationships(agent);
        if (!relations.isEmpty()) {
            sb.append("【你在群里的关系】\n").append(relations).append('\n');
        }
        sb.append("""
                【发言要求】
                - 用微信聊天口吻，像真人一样说话，允许拆成多条短句。
                - 严格贴合人设，绝不要暴露自己是 AI 或模型。
                - 长度随啰嗦程度变化；啰嗦程度低时可能只回几个字或一个表情。
                - emoji 使用随 emoji 倾向；表达欲低时可以不那么积极。
                - 一切时间、日期、白天黑夜、工作日/周末/假期/调休，都以【当前时间】为准；绝不要凭空臆测"大晚上的""周末了吧"之类。
                - 只输出你"要说的话"本身：不要加引号、不要写"某某说："前缀、不要解释。
                """);
        return sb.toString();
    }

    /** 门控用户提示。 */
    public static String gateUserPrompt(AgentDef agent, List<MessageDto> recent, MessageDto trigger,
            boolean mentioned, List<String> memories, String nowLine) {
        StringBuilder sb = new StringBuilder();
        sb.append("【你要扮演的成员】\n")
                .append("昵称：").append(agent.getName()).append("  (id=").append(agent.getId()).append(")\n")
                .append("人设：").append(nvl(agent.getPersona())).append('\n')
                .append("表达欲基准 ").append(agent.getEagernessBias()).append("/10；")
                .append("啰嗦程度 ").append(agent.getVerbosity()).append("/10；")
                .append("回应速度 ").append(agent.getResponseSpeed()).append("/10。\n");
        String relations = renderRelationships(agent);
        if (!relations.isEmpty()) {
            sb.append("群内关系：\n").append(relations).append('\n');
        }
        if (memories != null && !memories.isEmpty()) {
            sb.append("【你记得的事（过去的印象/群共识，不是此刻正在聊的内容）】\n");
            memories.forEach(m -> sb.append("- ").append(m).append('\n'));
        }
        appendTime(sb, nowLine);
        sb.append("【最近群聊】\n").append(transcript(recent)).append('\n');
        sb.append("【最新一条消息】").append(describe(trigger)).append('\n');
        if (mentioned) {
            sb.append("注意：你被点名 @ 了。除非你确实在忙或想装没看见，否则应当倾向于发言。\n");
        }
        sb.append("请判断：你现在想发言吗？表达欲 0~10 是多少？如果想说话，一句话概括你想说什么。\n");
        sb.append("请严格按下面的 JSON 格式输出，只输出这段 JSON：\n").append(GATE_JSON_SAMPLE);
        return sb.toString();
    }

    /** 发言用户提示。 */
    public static String speakUserPrompt(AgentDef agent, List<MessageDto> recent, MessageDto trigger,
            List<String> memories, String nowLine) {
        StringBuilder sb = new StringBuilder();
        appendTime(sb, nowLine);
        sb.append("【最近群聊】\n").append(transcript(recent)).append('\n');
        sb.append("【最新消息】").append(describe(trigger)).append('\n');
        if (memories != null && !memories.isEmpty()) {
            sb.append("【你记得的事（过去的印象/群共识，可自然引用，但别当成现在正在聊的内容）】\n");
            memories.forEach(m -> sb.append("- ").append(m).append('\n'));
        }
        sb.append("【现在轮到你发言】请以「").append(agent.getName()).append("」的身份说一句（可多句，按人设）。");
        return sb.toString();
    }

    /** 冷场主动开题提示（B2）：由话痨型人设率先打破沉默。 */
    public static String idleTopicUserPrompt(AgentDef agent, List<MessageDto> recent, String nowLine) {
        StringBuilder sb = new StringBuilder();
        appendTime(sb, nowLine);
        sb.append("【最近群聊】\n").append(transcript(recent)).append('\n');
        sb.append("【现在】群里安静了一会儿，没人说话了。你作为爱热闹、话痨的成员，主动起一个新话题，")
                .append("把气氛带起来。话题要自然、贴合你的兴趣，最好能抛出问题让大家好接话。\n");
        sb.append("请以「").append(agent.getName()).append("」的身份发言。");
        return sb.toString();
    }

    /**
     * 将最近消息渲染为聊天记录文本，撤回消息标记为已撤回（C3）。
     *
     * <p>跨天或断档超过 30 分钟处插入时间标签（如 {@code [今天 14:43]}），让模型对"当下"有概念。
     * 只在必要处插一行，避免每条都带时间戳浪费 token。
     */
    public static String transcript(List<MessageDto> messages) {
        StringBuilder sb = new StringBuilder();
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        LocalDate today = LocalDate.now();
        LocalDateTime prev = null;
        for (MessageDto m : messages) {
            LocalDateTime at = TimeUtil.parse(m.getCreatedAt());
            if (at != null) {
                boolean newDay = prev == null || !prev.toLocalDate().equals(at.toLocalDate());
                boolean longGap = prev != null && Duration.between(prev, at).toMinutes() >= 30;
                if (newDay || longGap) {
                    sb.append(TimeUtil.dayStamp(at.toLocalDate(), at.toLocalTime(), today)).append('\n');
                }
                prev = at;
            }
            if (MessageDto.TYPE_SYSTEM.equals(m.getSenderType())) {
                sb.append("【系统】").append(m.getContent()).append('\n');
                continue;
            }
            String content = m.isRecalled() ? "[撤回了消息]" : m.getContent();
            sb.append(m.getSenderName()).append("：").append(content).append('\n');
        }
        return sb.toString();
    }

    private static String describe(MessageDto m) {
        if (m == null) {
            return "(无)";
        }
        String content = m.isRecalled() ? "[撤回了消息]" : m.getContent();
        return "来自「" + m.getSenderName() + "」：" + content;
    }

    /**
     * 渲染群内关系为自然语言，供 prompt 使用。
     *
     * <p>DB 里存的是 JSON（{"对方":{"familiarity":8,"affinity":9,"meme":"总爱逗她"}}）。此前的实现直接把 value 交给
     * {@code Map.toString()}，模型看到的是 {@code {familiarity=8, affinity=9, meme=总爱逗她}}
     * 这种"英文 key + Java 花括号"，既费 token 又容易理解偏。这里统一翻成中文标签。
     *
     * <p>兼容性：若字段里存的不是 JSON（用户在管理页高级模式直接写了中文描述），
     * 则原样输出，不做任何加工。
     */
    private static String renderRelationships(AgentDef agent) {
        String raw = agent.getRelationships();
        if (raw == null || raw.isBlank()) {
            return "";
        }
        Map<String, Object> rel = agent.relationshipsMap();
        if (rel.isEmpty()) {
            return raw.strip() + '\n';
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : rel.entrySet()) {
            sb.append("- 与「").append(e.getKey()).append("」：")
                    .append(describeRelation(e.getValue())).append('\n');
        }
        return sb.toString();
    }

    /** 单条关系的自然语言化：已知维度翻成中文，未知维度原样列出，纯字符串直接用。 */
    private static String describeRelation(Object value) {
        if (value == null) {
            return "关系未知";
        }
        if (!(value instanceof Map<?, ?> m)) {
            return String.valueOf(value);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            Object v = e.getValue();
            if (v == null || String.valueOf(v).isBlank()) {
                continue;
            }
            switch (String.valueOf(e.getKey())) {
                case "familiarity" -> sb.append("熟悉度 ").append(v).append("/10，");
                case "affinity" -> sb.append("好感 ").append(v).append("/10，");
                case "meme" -> sb.append("内部梗/态度：").append(v).append("，");
                case "tone" -> sb.append("相处语气：").append(v).append("，");
                default -> sb.append(e.getKey()).append("：").append(v).append("，");
            }
        }
        if (sb.length() == 0) {
            return "关系未知";
        }
        return sb.substring(0, sb.length() - 1);
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}