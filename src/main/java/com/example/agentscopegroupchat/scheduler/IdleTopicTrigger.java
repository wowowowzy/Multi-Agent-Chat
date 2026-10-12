package com.example.agentscopegroupchat.scheduler;

import com.example.agentscopegroupchat.config.AppProperties;
import com.example.agentscopegroupchat.store.ChatStateDao;
import com.example.agentscopegroupchat.store.GroupDao;
import com.example.agentscopegroupchat.store.MessageDao;
import com.example.agentscopegroupchat.util.TimeUtil;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 冷场主动开题触发器（B2），多群版（E1）。
 *
 * <p>与小节 4.5 的"记忆空闲提取"是两件事，故单独一个定时组件（避免与 {@code MemoryService}
 * 的 {@code @Scheduled} 方法同名/同责）。这里只负责**逐个群**判断"是否已安静超过阈值"，
 * 然后委托 {@link GroupScheduler#triggerIdleTopic(String)} 由该群的话痨型人设率先开口。
 *
 * <p>防抖：开题本身会写入一条系统消息，从而刷新 {@code messages.lastMessageTime}，下一次轮询
 * 自然重新计时；进程内再按群维护一道 {@code lastFireAt} 兜底，防止同一轮静默反复开题。
 * 某个群报错不影响其它群。
 */
@Component
public class IdleTopicTrigger {

    private static final Logger log = LoggerFactory.getLogger(IdleTopicTrigger.class);

    private final AppProperties props;
    private final MessageDao messageDao;
    private final ChatStateDao chatStateDao;
    private final GroupDao groupDao;
    private final GroupScheduler groupScheduler;

    /** 每个群上次触发时间（进程内防抖，非持久化）。 */
    private final Map<String, Long> lastFireAt = new ConcurrentHashMap<>();

    public IdleTopicTrigger(AppProperties props, MessageDao messageDao, ChatStateDao chatStateDao,
            GroupDao groupDao, GroupScheduler groupScheduler) {
        this.props = props;
        this.messageDao = messageDao;
        this.chatStateDao = chatStateDao;
        this.groupDao = groupDao;
        this.groupScheduler = groupScheduler;
    }

    @Scheduled(fixedDelay = 10_000L, initialDelay = 20_000L)
    public void check() {
        List<String> groupIds;
        try {
            groupIds = groupDao.allIds();
        } catch (Exception e) {
            log.warn("冷场开题检查异常：{}", e.getMessage());
            return;
        }
        for (String groupId : groupIds) {
            try {
                checkOne(groupId);
            } catch (Exception e) {
                log.warn("群 {} 冷场开题检查异常：{}", groupId, e.getMessage());
            }
        }
    }

    private void checkOne(String groupId) {
        if (chatStateDao.isStopped(groupId)) {
            return; // 停止聊天开关：全群安静，不主动开题（C4）
        }
        LocalDateTime last = TimeUtil.parse(messageDao.lastMessageTime(groupId));
        if (last == null) {
            return; // 还没有任何消息，冷场无从谈起
        }
        long idleSec = Duration.between(last, LocalDateTime.now()).getSeconds();
        long threshold = props.getChat().getIdleTopicTriggerSec();
        if (idleSec < threshold) {
            return;
        }
        // 同一次静默至少间隔一个空闲阈值才再次开题
        long prev = lastFireAt.getOrDefault(groupId, 0L);
        if (System.currentTimeMillis() - prev < threshold * 1000L) {
            return;
        }
        lastFireAt.put(groupId, System.currentTimeMillis());
        // 说明：真正"是否开题"的判断（含暂停）在 GroupScheduler 里，日志也放在那儿，
        // 否则被暂停时会每 10 秒刷一条"触发冷场开题"的假日志。
        log.debug("群 {} 已安静 {} 秒（阈值 {} 秒），尝试冷场开题", groupId, idleSec, threshold);
        groupScheduler.triggerIdleTopic(groupId);
    }
}