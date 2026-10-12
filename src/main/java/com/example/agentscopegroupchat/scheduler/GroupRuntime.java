package com.example.agentscopegroupchat.scheduler;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单个群的运行期状态（E1 多群改造）。
 *
 * <p>多群之前，这些状态（generation / 正在生成的发言 / 冷场开题计数 / 轮次线程）都直接挂在
 * {@link GroupScheduler} 的字段上，结构上只能有一个群。拆成本类之后每个群各自独立：
 * 群 A 的真人插话只作废群 A 未发射的排队发言，不影响群 B；某个群在"打字延迟"里睡觉时，
 * 也不会堵住别的群的轮次。
 *
 * <p>注意：所有状态都是**进程内**的，重启即重置。持久化状态（停止开关、记忆进度）在 DB 里，
 * 由各自的 DAO 按 group_id 维护。
 */
final class GroupRuntime {

    private static final Logger log = LoggerFactory.getLogger(GroupRuntime.class);

    final String groupId;

    /** 轮次代际：每次新触发 +1，用来作废上一个未完成的轮次（C1 打断）。 */
    final AtomicLong generation = new AtomicLong();

    /**
     * 连续"全自动"轮数（冷场开题及其级联），其间没有任何真人发言。
     * 达到上限后暂停自发开题，避免真人不在场时白烧 token。
     */
    final AtomicInteger idleRoundsWithoutHuman = new AtomicInteger();

    /** 暂停提示只发一次。 */
    final AtomicBoolean idlePauseNotified = new AtomicBoolean();

    /** 每个群一个单线程执行器：整轮调度串行，保证节奏与可打断。 */
    final ExecutorService roundExecutor;

    /** 正在生成的 agent 与其上下文，用于打断（C1）。 */
    volatile ReActAgent inflightAgent;
    volatile RuntimeContext inflightCtx;

    GroupRuntime(String groupId) {
        this.groupId = groupId;
        this.roundExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "group-round-" + groupId));
    }

    long nextGeneration() {
        return generation.incrementAndGet();
    }

    void interruptInflight() {
        ReActAgent agent = inflightAgent;
        RuntimeContext ctx = inflightCtx;
        if (agent != null && ctx != null) {
            try {
                agent.interrupt(ctx);
            } catch (Exception e) {
                log.warn("打断正在生成的发言失败 group={}：{}", groupId, e.getMessage());
            }
        }
    }

    void shutdown() {
        roundExecutor.shutdownNow();
    }
}