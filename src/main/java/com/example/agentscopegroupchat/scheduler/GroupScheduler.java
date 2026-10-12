package com.example.agentscopegroupchat.scheduler;

import com.example.agentscopegroupchat.agent.AgentRegistry;
import com.example.agentscopegroupchat.agent.Prompts;
import com.example.agentscopegroupchat.config.AppProperties;
import com.example.agentscopegroupchat.dto.AgentDef;
import com.example.agentscopegroupchat.dto.DecisionDto;
import com.example.agentscopegroupchat.dto.GateDecision;
import com.example.agentscopegroupchat.dto.GroupDto;
import com.example.agentscopegroupchat.dto.MessageDto;
import com.example.agentscopegroupchat.dto.TokenUsageDto;
import com.example.agentscopegroupchat.event.SseHub;
import com.example.agentscopegroupchat.holiday.HolidayCalendar;
import com.example.agentscopegroupchat.memory.MemoryService;
import com.example.agentscopegroupchat.memory.RecallMemo;
import com.example.agentscopegroupchat.model.ModelProvider;
import com.example.agentscopegroupchat.store.AgentDao;
import com.example.agentscopegroupchat.store.ChatStateDao;
import com.example.agentscopegroupchat.store.DecisionLogDao;
import com.example.agentscopegroupchat.store.GroupDao;
import com.example.agentscopegroupchat.store.MessageDao;
import com.example.agentscopegroupchat.store.TokenUsageDao;
import com.example.agentscopegroupchat.util.TimeUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 群调度器（自研核心，4.1），支持多群（E1）。
 *
 * <p>流程：新消息 → 全员并行门控 → @ 硬规则覆盖 → 50% 配额裁决 → 节奏队列串行发射（拟人延迟）
 * → 每条 agent 发言再次触发门控（级联，深度上限）。真人插话时未发射的排队发言作废（C1）。
 *
 * <p>多群：所有可变状态（代际、正在生成的发言、冷场开题计数、轮次线程）都放在
 * {@link GroupRuntime} 里，按 groupId 懒创建。上下文的群成员取自 {@code group_members}，
 * 因此同一个 agent 在 A 群和 B 群是"同一个人的两场对话"，互不干扰。
 *
 * <p>并发：门控用固定小线程池并行（跨群共享，防并发打爆上游）；每群整轮调度在各自的
 * 单线程里串行执行。
 */
@Component
public class GroupScheduler {

    private static final Logger log = LoggerFactory.getLogger(GroupScheduler.class);

    private final AppProperties props;
    private final MessageDao messageDao;
    private final AgentDao agentDao;
    private final GroupDao groupDao;
    private final TokenUsageDao tokenUsageDao;
    private final DecisionLogDao decisionLogDao;
    private final ChatStateDao chatStateDao;
    private final AgentRegistry agentRegistry;
    private final MemoryService memoryService;
    private final ModelProvider modelProvider;
    private final SseHub sseHub;
    private final ObjectMapper mapper;
    private final HolidayCalendar holidayCalendar;

    /** 每群的运行期状态（懒创建）。 */
    private final Map<String, GroupRuntime> runtimes = new ConcurrentHashMap<>();

    private final ExecutorService gateExecutor = Executors.newFixedThreadPool(4,
            r -> new Thread(r, "group-gate"));
    private final Random random = new Random();

    public GroupScheduler(AppProperties props, MessageDao messageDao, AgentDao agentDao, GroupDao groupDao,
            TokenUsageDao tokenUsageDao, DecisionLogDao decisionLogDao, ChatStateDao chatStateDao,
            AgentRegistry agentRegistry, MemoryService memoryService, ModelProvider modelProvider,
            SseHub sseHub, ObjectMapper mapper, HolidayCalendar holidayCalendar) {
        this.props = props;
        this.messageDao = messageDao;
        this.agentDao = agentDao;
        this.groupDao = groupDao;
        this.tokenUsageDao = tokenUsageDao;
        this.decisionLogDao = decisionLogDao;
        this.chatStateDao = chatStateDao;
        this.agentRegistry = agentRegistry;
        this.memoryService = memoryService;
        this.modelProvider = modelProvider;
        this.sseHub = sseHub;
        this.mapper = mapper;
        this.holidayCalendar = holidayCalendar;
    }

    @PreDestroy
    public void shutdown() {
        runtimes.values().forEach(GroupRuntime::shutdown);
        gateExecutor.shutdownNow();
    }

    private GroupRuntime runtime(String groupId) {
        return runtimes.computeIfAbsent(groupId, GroupRuntime::new);
    }

    /** 群被解散时调用：停掉它的轮次线程并丢弃运行期状态。 */
    public void dispose(String groupId) {
        GroupRuntime rt = runtimes.remove(groupId);
        if (rt != null) {
            rt.interruptInflight();
            rt.shutdown();
        }
    }

    // ---- 内部数据结构 ----

    /** 单个 agent 的门控原始结果。 */
    private record GateResult(AgentDef agent, boolean want, int eagerness, String gist) {
    }

    /** 通过门控、进入配额裁决的候选。 */
    private record Candidate(AgentDef agent, int eagerness, String gist, String reason) {
    }

    /** 单轮结果。 */
    private record Outcome(boolean anySpoke, long lastAgentMessageId) {
    }

    // ---- 对外入口 ----

    /** 真人发消息（或系统消息）后触发调度。会作废该群上一轮未发射的排队发言。 */
    public void onHumanMessage(String groupId, long messageId) {
        GroupRuntime rt = runtime(groupId);
        // 真人回来发言 = 有人看了：清零"无人在场"计数，冷场开题恢复可用（加群公告等系统消息不重置）
        MessageDto trigger = messageDao.findById(messageId);
        if (trigger != null && MessageDto.TYPE_HUMAN.equals(trigger.getSenderType())) {
            int before = rt.idleRoundsWithoutHuman.getAndSet(0);
            if (before > 0) {
                log.info("群 {} 真人发言，冷场开题计数清零（此前已自动运行 {} 轮无人应答）", groupId, before);
            }
            rt.idlePauseNotified.set(false);
        }
        long gen = rt.nextGeneration();
        rt.interruptInflight();
        if (chatStateDao.isStopped(groupId)) {
            return; // 停止聊天开关：只存消息不触发决策（C4）
        }
        rt.roundExecutor.submit(() -> {
            try {
                startRound(rt, gen, messageId);
            } catch (Exception e) {
                log.error("群聊调度异常 group={}", groupId, e);
            }
        });
    }

    /** 冷场主动开题（B2）：由话痨型人设率先打破沉默。 */
    public void triggerIdleTopic(String groupId) {
        GroupRuntime rt = runtime(groupId);
        // 无人在场就别自嗨：连续 N 轮全自动、真人一条没回，就停在这里（避免白烧 token）
        int maxIdle = props.getChat().getMaxIdleRoundsWithoutHuman();
        if (maxIdle > 0 && rt.idleRoundsWithoutHuman.get() >= maxIdle) {
            notifyIdlePausedOnce(rt, maxIdle);
            return;
        }
        long gen = rt.nextGeneration();
        rt.interruptInflight();
        if (chatStateDao.isStopped(groupId)) {
            return;
        }
        rt.roundExecutor.submit(() -> {
            try {
                List<AgentDef> agents = agentDao.findByGroup(groupId);
                AgentDef talkative = agents.stream().filter(AgentDef::isTalkative).findFirst().orElse(null);
                if (talkative == null) {
                    return; // 群是空的，或没有话痨型成员
                }
                rt.idleRoundsWithoutHuman.incrementAndGet();
                log.info("群 {} 冷场开题：连续第 {} 轮无真人应答（上限 {}，真人发言后清零）", groupId,
                        rt.idleRoundsWithoutHuman.get(), maxIdle);
                MessageDto sys = saveMessage(groupId, "system", MessageDto.TYPE_SYSTEM, "系统",
                        "（群里安静了一会儿，有人率先打破了沉默）", "📢");
                sseHub.publish("system", sys);
                if (!modelProvider.isChatConfigured()) {
                    pushSystemHint(groupId);
                    return;
                }
                speakOne(rt, talkative, sys, gen, true, null);
                memoryService.maybeExtractByCount(groupId);
            } catch (Exception e) {
                log.error("冷场开题异常 group={}", groupId, e);
            }
        });
    }

    /** 因长时间无人应答而暂停自发开题时，往群里留一条系统消息说明原因（只发一次）。 */
    private void notifyIdlePausedOnce(GroupRuntime rt, int maxIdle) {
        if (!rt.idlePauseNotified.compareAndSet(false, true)) {
            return;
        }
        log.info("群 {} 连续 {} 轮冷场开题都没有真人回复，暂停自发开题（真人发言后自动恢复）", rt.groupId, maxIdle);
        MessageDto sys = saveMessage(rt.groupId, "system", MessageDto.TYPE_SYSTEM, "系统",
                "（连着聊了 " + maxIdle + " 轮都没人接话，大家先歇会儿 —— 你说句话就活过来了）", "📢");
        sseHub.publish("system", sys);
    }

    // ---- 单轮调度 ----

    private void startRound(GroupRuntime rt, long gen, long triggerMessageId) {
        String groupId = rt.groupId;
        if (!modelProvider.isGateConfigured()) {
            pushSystemHint(groupId);
            return;
        }
        int maxDepth = props.getChat().getCascadeMaxDepth();
        long trigger = triggerMessageId;
        int depth = 0;
        while (depth <= maxDepth) {
            if (gen != rt.generation.get() || chatStateDao.isStopped(groupId)) {
                return;
            }
            Outcome outcome = runOneRound(rt, gen, depth, trigger);
            if (outcome == null) {
                return;
            }
            if (!outcome.anySpoke()) {
                break; // 没人接话，自然冷场
            }
            trigger = outcome.lastAgentMessageId();
            depth++;
        }
        if (gen == rt.generation.get()) {
            memoryService.maybeExtractByCount(groupId);
        }
    }

    private Outcome runOneRound(GroupRuntime rt, long gen, int depth, long triggerMessageId) {
        String groupId = rt.groupId;
        List<AgentDef> agents = agentDao.findByGroup(groupId);
        if (agents.isEmpty()) {
            return new Outcome(false, triggerMessageId);
        }
        MessageDto trigger = messageDao.findById(triggerMessageId);
        if (trigger == null) {
            return new Outcome(false, triggerMessageId);
        }
        List<MessageDto> recent = messageDao.findRecent(groupId, props.getChat().getContextWindowK());
        Set<String> mentioned = MentionParser.mentionedAgentIds(trigger.getContent(), agents);

        // 1) 队列内全员并行门控（先亮起"正在看消息"，否则这几秒前端一片安静）
        publishThinking(groupId, triggerMessageId, depth, agents, true);
        List<Future<GateResult>> futures = new ArrayList<>();
        for (AgentDef agent : agents) {
            boolean isMentioned = mentioned.contains(agent.getId());
            futures.add(gateExecutor.submit(() -> gateOne(groupId, agent, recent, trigger, isMentioned)));
        }
        List<GateResult> results = new ArrayList<>();
        for (Future<GateResult> f : futures) {
            try {
                GateResult r = f.get();
                if (r != null) {
                    results.add(r);
                }
            } catch (Exception e) {
                log.warn("门控任务异常：{}", e.getMessage());
            }
        }
        if (gen != rt.generation.get()) {
            return null;
        }

        // 2) @ 提及硬规则覆盖（含 B5 极小概率"装没看见"）
        List<Candidate> candidates = new ArrayList<>();
        Map<String, DecisionDto> decisions = new LinkedHashMap<>();
        double ignoreProb = props.getChat().getMentionIgnoreProbability();
        for (GateResult r : results) {
            AgentDef a = r.agent();
            boolean isMentioned = mentioned.contains(a.getId());
            boolean want = r.want();
            int eagerness = r.eagerness();
            String reason = null;
            if (isMentioned) {
                if (!want) {
                    if (random.nextDouble() < ignoreProb) {
                        reason = "被 @ 但装没看见";
                    } else {
                        want = true;
                        eagerness = Math.max(eagerness, 7);
                        reason = "被 @ 强制进候选";
                    }
                } else {
                    reason = "被 @ 且本就发言";
                }
            }
            DecisionDto d = new DecisionDto();
            d.setMessageId(triggerMessageId);
            d.setAgentId(a.getId());
            d.setWantToSpeak(want);
            d.setEagerness(eagerness);
            d.setGist(r.gist());
            decisions.put(a.getId(), d);
            if (want) {
                candidates.add(new Candidate(a, eagerness, r.gist(), reason));
            } else if (reason == null) {
                d.setReason("无发言意愿");
            } else {
                d.setReason(reason);
            }
        }

        // 3) 50% 配额裁决
        int quota = QuotaArbiter.quota(agents.size(), props.getChat().getSpeakQuotaPercent());
        List<Candidate> selected = QuotaArbiter.select(candidates, Candidate::eagerness, quota, random);
        Set<String> selectedIds = new java.util.HashSet<>();
        selected.forEach(c -> selectedIds.add(c.agent().getId()));
        for (Candidate c : candidates) {
            DecisionDto d = decisions.get(c.agent().getId());
            if (selectedIds.contains(c.agent().getId())) {
                d.setOutcome(DecisionDto.OUTCOME_SELECTED);
                d.setReason(c.reason() != null ? c.reason() : "表达欲入选");
            } else {
                d.setOutcome(DecisionDto.OUTCOME_SKIPPED);
                d.setReason("表达欲不足，被 " + props.getChat().getSpeakQuotaPercent() + "% 配额刷掉");
            }
        }
        decisions.values().forEach(d -> {
            if (d.getOutcome() == null) {
                d.setOutcome(DecisionDto.OUTCOME_SILENT);
            }
            decisionLogDao.insert(d);
        });

        // 决策透视通过 SSE 广播（D1）；带上 groupId，前端只渲染当前群的事件
        Map<String, Object> decisionEvent = new LinkedHashMap<>();
        decisionEvent.put("groupId", groupId);
        decisionEvent.put("messageId", triggerMessageId);
        decisionEvent.put("depth", depth);
        decisionEvent.put("quota", quota);
        decisionEvent.put("decisions", new ArrayList<>(decisions.values()));
        sseHub.publish("decision", decisionEvent);

        // 3.5) 门控结束，收起"正在看消息"；被 @ 却没等到回音的，给真人一条实时提示
        publishThinking(groupId, triggerMessageId, depth, agents, false);
        boolean mentionMiss = false;
        for (String mentionId : mentioned) {
            DecisionDto d = decisions.get(mentionId);
            if (d == null || DecisionDto.OUTCOME_SELECTED.equals(d.getOutcome())) {
                continue;
            }
            AgentDef a = findAgent(agents, mentionId);
            if (a == null) {
                continue;
            }
            String text = mentionMissText(a.getName(), d.getOutcome());
            publishNotice(groupId, triggerMessageId, "mention-miss", a, text);
            mentionMiss = true;
        }

        // 4) 节奏队列串行发射
        boolean anySpoke = false;
        long lastAgentMessageId = triggerMessageId;
        // 同一轮所有入选 agent 的召回 query 都是这条触发消息：共享一次 embedding 与一次
        // 「群共识」检索，避免 N 个 agent 重复 N 次（见 RecallMemo）
        RecallMemo recallMemo = memoryService.newRecallMemo(trigger.getContent());
        for (Candidate c : selected) {
            if (gen != rt.generation.get()) {
                break; // 真人插话，未发射的排队发言作废（C1）
            }
            MessageDto spoken = speakOne(rt, c.agent(), trigger, gen, false, recallMemo);
            if (spoken != null) {
                anySpoke = true;
                lastAgentMessageId = spoken.getId();
            }
        }
        // 真人发话却全员已读不回（且不是 @ 未理睬那种更具体的情形）：给个提示，别让用户以为卡了
        if (!anySpoke && depth == 0 && !mentionMiss
                && MessageDto.TYPE_HUMAN.equals(trigger.getSenderType())) {
            publishNotice(groupId, triggerMessageId, "read-no-reply", null,
                    "（这条消息暂时没人接话 —— 试试直接 @ 某个人点名）");
        }
        return new Outcome(anySpoke, lastAgentMessageId);
    }

    // ---- 门控 ----

    private GateResult gateOne(String groupId, AgentDef agent, List<MessageDto> recent, MessageDto trigger,
            boolean mentioned) {
        ReActAgent gateAgent = agentRegistry.newGateAgent();
        if (gateAgent == null) {
            return new GateResult(agent, false, 0, null);
        }
        try {
            String prompt = Prompts.gateUserPrompt(agent, recent, trigger, mentioned, List.of(),
                    holidayCalendar.nowLine());
            RuntimeContext ctx = RuntimeContext.builder().userId(agent.getId()).sessionId(groupId).build();
            Msg res = gateAgent.call(List.of(new UserMessage(prompt)), GateDecision.class, ctx).block();
            recordUsage(groupId, agent.getId(), TokenUsageDto.PURPOSE_GATE, res);
            GateDecision d;
            try {
                d = res == null ? null : res.getStructuredData(GateDecision.class);
            } catch (Exception e) {
                // 部分兼容接口在模型未产出结构化结果时，getStructuredData 会直接抛异常
                // （message metadata 缺少 _structured_output），此时转到宽松 JSON 解析兜底。
                d = null;
            }
            if (d == null && res != null) {
                d = lenientGate(res.getTextContent());
            }
            if (d == null) {
                return new GateResult(agent, false, 0, null);
            }
            int eagerness = Math.max(0, Math.min(10, d.getEagerness()));
            return new GateResult(agent, d.isWantToSpeak(), eagerness, d.getGist());
        } catch (Exception e) {
            log.warn("门控失败 agent={}：{}", agent.getName(), e.getMessage());
            return new GateResult(agent, false, 0, null);
        } finally {
            gateAgent.close();
        }
    }

    private GateDecision lenientGate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return mapper.readValue(text.substring(start, end + 1), GateDecision.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- 发言 ----

    private MessageDto speakOne(GroupRuntime rt, AgentDef agent, MessageDto trigger, long gen, boolean startTopic,
            RecallMemo recallMemo) {
        String groupId = rt.groupId;
        if (gen != rt.generation.get()) {
            return null;
        }
        // 轮到它开口：立刻亮起"正在酝酿"，让拟人延迟期间真人也能看到有人在准备（而不是以为卡了）
        publishTyping(groupId, agent, true, TYPING_WAITING);
        try {
            // 拟人打字延迟
            long delay = computeDelay(agent);
            if (!sleepInterruptible(delay, rt, gen)) {
                return null;
            }
            if (gen != rt.generation.get()) {
                return null;
            }

            publishTyping(groupId, agent, true, TYPING_COMPOSING);
            GroupDto group = groupDao.findById(groupId);
            ReActAgent chatAgent = agentRegistry.newChatAgent(agent,
                    group == null ? props.getChat().getGroupName() : group.getName(),
                    group == null ? null : group.getNotice());
            if (chatAgent == null) {
                pushSystemHint(groupId);
                return null;
            }
            try {
                List<MessageDto> recent = messageDao.findRecent(groupId, props.getChat().getContextWindowK());
                String prompt = startTopic
                        ? Prompts.idleTopicUserPrompt(agent, recent, holidayCalendar.nowLine())
                        : Prompts.speakUserPrompt(agent, recent, trigger,
                                memoryService.recall(groupId, agent.getId(), trigger.getContent(), recallMemo),
                                holidayCalendar.nowLine());
                RuntimeContext ctx = RuntimeContext.builder().userId(agent.getId()).sessionId(groupId).build();

                Msg res;
                rt.inflightAgent = chatAgent;
                rt.inflightCtx = ctx;
                try {
                    res = chatAgent.call(List.of(new UserMessage(prompt)), ctx).block();
                } catch (Exception e) {
                    log.warn("发言生成失败 agent={}：{}", agent.getName(), e.getMessage());
                    return null;
                } finally {
                    rt.inflightAgent = null;
                    rt.inflightCtx = null;
                }
                if (gen != rt.generation.get()) {
                    return null; // 被打断：丢弃这条未发出的发言（C1）
                }
                String text = postProcess(res == null ? "" : res.getTextContent());
                if (text.isBlank()) {
                    return null;
                }
                recordUsage(groupId, agent.getId(), TokenUsageDto.PURPOSE_SPEAK, res);
                MessageDto msg = saveMessage(groupId, agent.getId(), MessageDto.TYPE_AGENT, agent.getName(), text,
                        agent.getAvatar());
                sseHub.publish("message", msg);
                return msg;
            } finally {
                chatAgent.close();
            }
        } finally {
            publishTyping(groupId, agent, false, null);
        }
    }

    /** 打字指示的两个阶段：waiting=轮到它、正在酝酿（拟人延迟中）；composing=正在生成回复（真在打字）。 */
    private static final String TYPING_WAITING = "waiting";
    private static final String TYPING_COMPOSING = "composing";

    private void publishTyping(String groupId, AgentDef agent, boolean active, String phase) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("groupId", groupId);
        data.put("agentId", agent.getId());
        data.put("name", agent.getName());
        data.put("avatar", agent.getAvatar() == null ? "" : agent.getAvatar());
        data.put("active", active);
        if (phase != null) {
            data.put("phase", phase);
        }
        sseHub.publish("typing", data);
    }

    /** 门控阶段提示：让真人知道"大家都在看这条消息"，而不是对着空气等、以为卡了。 */
    private void publishThinking(String groupId, long messageId, int depth, List<AgentDef> agents, boolean active) {
        List<String> names = new ArrayList<>();
        for (AgentDef a : agents) {
            names.add(a.getName());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("groupId", groupId);
        data.put("messageId", messageId);
        data.put("depth", depth);
        data.put("count", agents.size());
        data.put("names", names);
        data.put("active", active);
        sseHub.publish("thinking", data);
    }

    /**
     * 实时提示（不落库）：例如"被 @ 了却没回你"。给真人明确反馈，避免把"对方不想理"误解成"程序卡住"。
     */
    private void publishNotice(String groupId, long messageId, String kind, AgentDef agent, String text) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("groupId", groupId);
        data.put("messageId", messageId);
        data.put("kind", kind);
        if (agent != null) {
            data.put("agentId", agent.getId());
            data.put("name", agent.getName());
            data.put("avatar", agent.getAvatar() == null ? "" : agent.getAvatar());
        }
        data.put("text", text);
        sseHub.publish("notice", data);
    }

    /** 拟人打字延迟：基线随机 + 回应速度修正（快嘴更短、高冷更长）。 */
    private long computeDelay(AgentDef agent) {
        AppProperties.Chat chat = props.getChat();
        long min = Math.max(100, chat.getTypingDelayMinMs());
        long max = Math.max(min, chat.getTypingDelayMaxMs());
        long base = min + (long) (random.nextDouble() * (max - min));
        double factor = 1.0 + (5 - agent.getResponseSpeed()) * 0.12;
        return Math.max(300, (long) (base * factor));
    }

    private boolean sleepInterruptible(long millis, GroupRuntime rt, long gen) {
        long remaining = millis;
        while (remaining > 0) {
            if (gen != rt.generation.get()) {
                return false;
            }
            long step = Math.min(150, remaining);
            try {
                Thread.sleep(step);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            remaining -= step;
        }
        return gen == rt.generation.get();
    }

    // ---- 工具方法 ----

    private AgentDef findAgent(List<AgentDef> agents, String id) {
        for (AgentDef a : agents) {
            if (a.getId().equals(id)) {
                return a;
            }
        }
        return null;
    }

    /**
     * 被 @ 却没等到回音时，给真人的提示文案。装没看见（SILENT）与想接话但被配额刷掉（SKIPPED）语义不同，
     * 分开措辞，避免把"没抢到话头"也说成"不理你"。
     */
    static String mentionMissText(String name, String outcome) {
        return DecisionDto.OUTCOME_SKIPPED.equals(outcome)
                ? name + " 想接话，但没抢到话头"
                : name + " 好像没理睬你 🙈";
    }

    private MessageDto saveMessage(String groupId, String senderId, String senderType, String senderName,
            String content, String avatar) {
        MessageDto m = new MessageDto();
        m.setGroupId(groupId);
        m.setSenderId(senderId);
        m.setSenderType(senderType);
        m.setSenderName(senderName);
        m.setContent(content);
        m.setAvatar(avatar);
        m.setCreatedAt(TimeUtil.now());
        long id = messageDao.insert(m);
        m.setId(id);
        return m;
    }

    private void recordUsage(String groupId, String agentId, String purpose, Msg res) {
        if (res == null || res.getUsage() == null) {
            return;
        }
        int in = res.getUsage().getInputTokens();
        int out = res.getUsage().getOutputTokens();
        if (in == 0 && out == 0) {
            return;
        }
        tokenUsageDao.insert(groupId, agentId, purpose, in, out);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("groupId", groupId);
        data.put("agentId", agentId);
        data.put("purpose", purpose);
        data.put("inputTokens", in);
        data.put("outputTokens", out);
        data.put("groupTokens", tokenUsageDao.totalTokens(groupId));
        data.put("totalTokens", tokenUsageDao.totalTokens());
        sseHub.publish("usage", data);
    }

    private void pushSystemHint(String groupId) {
        sseHub.publish("system", Map.of("groupId", groupId,
                "content", "⚠️ 模型未配置：请在 application.yml 填写 app.model.chat / gate 的 base-url、api-key、model 后重试。"));
    }

    /** 清理模型输出：去掉包裹引号与"某某说："前缀，限制长度。 */
    private String postProcess(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        if (t.length() >= 2
                && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("“") && t.endsWith("”")))) {
            t = t.substring(1, t.length() - 1).trim();
        }
        t = t.replaceFirst("^[^：:\\n]{1,12}(说|回复|发言)[：:]\\s*", "");
        if (t.length() > 600) {
            t = t.substring(0, 600);
        }
        return t.trim();
    }
}