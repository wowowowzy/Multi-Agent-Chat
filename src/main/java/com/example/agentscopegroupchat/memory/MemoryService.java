package com.example.agentscopegroupchat.memory;

import com.example.agentscopegroupchat.config.AppProperties;
import com.example.agentscopegroupchat.store.GroupDao;
import com.example.agentscopegroupchat.store.MemoryDao;
import com.example.agentscopegroupchat.store.MessageDao;
import com.example.agentscopegroupchat.util.TimeUtil;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 记忆服务入口：触发策略（累计 N 条 / 空闲 N 秒）+ 召回，供调度器调用。
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private final AppProperties props;
    private final MemoryExtractor extractor;
    private final MemoryRetriever retriever;
    private final MessageDao messageDao;
    private final MemoryDao memoryDao;
    private final GroupDao groupDao;

    public MemoryService(AppProperties props, MemoryExtractor extractor, MemoryRetriever retriever,
            MessageDao messageDao, MemoryDao memoryDao, GroupDao groupDao) {
        this.props = props;
        this.extractor = extractor;
        this.retriever = retriever;
        this.messageDao = messageDao;
        this.memoryDao = memoryDao;
        this.groupDao = groupDao;
    }

    public boolean isEnabled() {
        return props.getMemory().isEnabled();
    }

    /** 召回注入发言 prompt 的记忆。 */
    public List<String> recall(String groupId, String agentId, String queryText) {
        return recall(groupId, agentId, queryText, null);
    }

    /**
     * 带单轮备忘的召回：同一轮所有入选 agent 的 queryText 相同，借此复用一次 embedding
     * 与一次「群共识」检索（见 {@link RecallMemo}），避免 N 个 agent 重复 N 次。
     */
    public List<String> recall(String groupId, String agentId, String queryText, RecallMemo memo) {
        return retriever.recall(groupId, agentId, queryText, memo);
    }

    /** 为一轮召回创建备忘（懒计算：若无人调用 recall，则不会产生任何计算/请求）。 */
    public RecallMemo newRecallMemo(String queryText) {
        return new RecallMemo(queryText);
    }

    /** 提交消息后按"累计 N 条"触发一次提取（异步由调度器线程承担）。 */
    public void maybeExtractByCount(String groupId) {
        if (!isEnabled()) {
            return;
        }
        long lastId = memoryDao.getLastExtractMessageId(groupId);
        long count = messageDao.countSince(groupId, lastId);
        if (count >= props.getMemory().getTriggerMsgCount()) {
            extractor.extract(groupId);
        }
    }

    /** 定时任务：逐个群检查——空闲 N 秒且有未提取消息时触发提取。 */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void idleExtract() {
        if (!isEnabled()) {
            return;
        }
        for (String groupId : groupDao.allIds()) {
            try {
                extractIfIdle(groupId);
            } catch (Exception e) {
                // 单个群失败不影响其它群
                log.warn("群 {} 空闲记忆提取失败：{}", groupId, e.getMessage());
            }
        }
    }

    private void extractIfIdle(String groupId) {
        long lastId = memoryDao.getLastExtractMessageId(groupId);
        if (messageDao.countSince(groupId, lastId) <= 0) {
            return;
        }
        String lastTime = messageDao.lastMessageTime(groupId);
        LocalDateTime last = TimeUtil.parse(lastTime);
        if (last == null) {
            return;
        }
        long idleSec = Duration.between(last, LocalDateTime.now()).getSeconds();
        if (idleSec >= props.getMemory().getTriggerIdleSec()) {
            extractor.extract(groupId);
        }
    }
}