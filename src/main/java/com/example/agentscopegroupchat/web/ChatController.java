package com.example.agentscopegroupchat.web;

import com.example.agentscopegroupchat.config.AppProperties;
import com.example.agentscopegroupchat.dto.MessageDto;
import com.example.agentscopegroupchat.event.SseHub;
import com.example.agentscopegroupchat.scheduler.GroupScheduler;
import com.example.agentscopegroupchat.store.GroupDao;
import com.example.agentscopegroupchat.store.MessageDao;
import com.example.agentscopegroupchat.util.TimeUtil;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息接口（5.3）：发消息、历史分页、撤回。多群版（E1）：group 通过参数指定，
 * 缺省回退到示例群（即多群改造前的那个唯一群），保证老书签/老调用仍然可用。
 *
 * <p>真人消息落库后立即通过 SSE 广播，并异步触发**该群**的调度
 * （见 {@link GroupScheduler#onHumanMessage(String, long)}）。
 */
@RestController
@RequestMapping("/api/messages")
public class ChatController {

    private static final int MAX_LIMIT = 200;
    private static final String HUMAN_NAME = "我";
    private static final String HUMAN_AVATAR = "🧑";

    private final AppProperties props;
    private final MessageDao messageDao;
    private final GroupDao groupDao;
    private final GroupScheduler scheduler;
    private final SseHub sseHub;

    public ChatController(AppProperties props, MessageDao messageDao, GroupDao groupDao,
            GroupScheduler scheduler, SseHub sseHub) {
        this.props = props;
        this.messageDao = messageDao;
        this.groupDao = groupDao;
        this.scheduler = scheduler;
        this.sseHub = sseHub;
    }

    public record SendRequest(String content, String groupId) {
    }

    @PostMapping
    public ResponseEntity<?> send(@RequestBody SendRequest req) {
        if (req == null || req.content() == null || req.content().isBlank()) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "消息内容不能为空"));
        }
        String groupId = resolveGroup(req.groupId());
        if (groupId == null || !groupDao.exists(groupId)) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "群聊不存在"));
        }
        String content = req.content().trim();
        if (content.length() > 2000) {
            content = content.substring(0, 2000);
        }
        MessageDto m = new MessageDto();
        m.setGroupId(groupId);
        m.setSenderId("human");
        m.setSenderType(MessageDto.TYPE_HUMAN);
        m.setSenderName(HUMAN_NAME);
        m.setAvatar(HUMAN_AVATAR);
        m.setContent(content);
        m.setCreatedAt(TimeUtil.now());
        long id = messageDao.insert(m);
        m.setId(id);
        sseHub.publish("message", m);
        // 异步：门控 → 配额 → 发言（真人插话会让该群上一轮未发射的排队发言作废，C1）
        scheduler.onHumanMessage(groupId, id);
        return ResponseEntity.ok(m);
    }

    /**
     * 历史分页（C5）。{@code before} 为空时返回最近 limit 条；否则返回 id 小于 before 的更早消息。
     * 结果按时间升序返回，便于前端整体追加。
     */
    @GetMapping
    public List<MessageDto> history(@RequestParam(name = "groupId", required = false) String groupIdParam,
            @RequestParam(name = "before", required = false) Long before,
            @RequestParam(name = "limit", defaultValue = "30") int limit) {
        int n = Math.max(1, Math.min(MAX_LIMIT, limit));
        String groupId = resolveGroup(groupIdParam);
        if (groupId == null) {
            return List.of();
        }
        if (before == null || before <= 0) {
            return messageDao.findRecent(groupId, n);
        }
        return messageDao.findBefore(groupId, before, n);
    }

    /** 撤回消息（C3）：标记 recalled 并广播，agent 视野中该消息显示为已撤回。 */
    @PostMapping("/{id}/recall")
    public ResponseEntity<MessageDto> recall(@PathVariable("id") long id) {
        MessageDto m = messageDao.findById(id);
        if (m == null) {
            return ResponseEntity.notFound().build();
        }
        messageDao.recall(id);
        m.setRecalled(true);
        sseHub.publish("recall", m);
        return ResponseEntity.ok(m);
    }

    /** 空 → 配置里的示例群；非空 → 原样返回（合法性由调用方校验）。 */
    private String resolveGroup(String groupId) {
        return groupId == null || groupId.isBlank() ? props.getChat().getGroupId() : groupId;
    }
}