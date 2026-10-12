package com.example.agentscopegroupchat.web;

import com.example.agentscopegroupchat.dto.AgentDef;
import com.example.agentscopegroupchat.dto.GroupDto;
import com.example.agentscopegroupchat.dto.MessageDto;
import com.example.agentscopegroupchat.event.SseHub;
import com.example.agentscopegroupchat.memory.VectorStore;
import com.example.agentscopegroupchat.scheduler.GroupScheduler;
import com.example.agentscopegroupchat.store.AgentDao;
import com.example.agentscopegroupchat.store.ChatStateDao;
import com.example.agentscopegroupchat.store.GroupDao;
import com.example.agentscopegroupchat.store.MemoryDao;
import com.example.agentscopegroupchat.store.MessageDao;
import com.example.agentscopegroupchat.util.TimeUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 群聊管理（E1 多群）：列表 / 新建（选了哪些 agent 当成员）/ 改名 / 解散 / 群成员增删。
 *
 * <p>人设是全局共享的，群成员关系在 {@code group_members}；因此同一个 agent 可以同时待在
 * 多个群里。示例群（is_sample=1）不允许解散。
 */
@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private static final int MAX_NAME_LEN = 30;

    private final GroupDao groupDao;
    private final AgentDao agentDao;
    private final MessageDao messageDao;
    private final ChatStateDao chatStateDao;
    private final MemoryDao memoryDao;
    private final VectorStore vectorStore;
    private final GroupScheduler scheduler;
    private final SseHub sseHub;

    public GroupController(GroupDao groupDao, AgentDao agentDao, MessageDao messageDao,
            ChatStateDao chatStateDao, MemoryDao memoryDao, VectorStore vectorStore, GroupScheduler scheduler,
            SseHub sseHub) {
        this.groupDao = groupDao;
        this.agentDao = agentDao;
        this.messageDao = messageDao;
        this.chatStateDao = chatStateDao;
        this.memoryDao = memoryDao;
        this.vectorStore = vectorStore;
        this.scheduler = scheduler;
        this.sseHub = sseHub;
    }

    @GetMapping
    public List<GroupDto> list() {
        return groupDao.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<GroupDto> get(@PathVariable("id") String id) {
        GroupDto g = groupDao.findById(id);
        return g == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(g);
    }

    public record CreateRequest(String name, String avatar, String notice, List<String> agentIds) {
    }

    /** 新建群聊：必须选至少一个成员（就是"拉几个 agent 进来"这一步）。 */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            return bad("群名称不能为空");
        }
        String name = trimName(req.name());
        List<String> ids = validAgentIds(req.agentIds());
        if (ids.isEmpty()) {
            return bad("请至少选择一个成员");
        }
        String id = "g-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        groupDao.insert(id, name, blankToNull(req.avatar()), blankToNull(req.notice()), false);
        ids.forEach(a -> groupDao.addMember(id, a));
        chatStateDao.ensure(id);

        // 开场：写一条系统提示条并触发一轮调度，让成员们自己开聊
        MessageDto sys = saveSystemMessage(id, "「" + name + "」建好了，" + names(ids) + "加入了群聊，聊两句吧～");
        scheduler.onHumanMessage(id, sys.getId());

        publishGroupsChanged();
        return ResponseEntity.ok(groupDao.findById(id));
    }

    public record UpdateRequest(String name, String avatar, String notice) {
    }

    /** 改群名 / 群头像 / 群公告。 */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable("id") String id, @RequestBody UpdateRequest req) {
        GroupDto existing = groupDao.findById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (req == null) {
            return bad("参数为空");
        }
        String name = req.name() == null || req.name().isBlank() ? existing.getName() : trimName(req.name());
        String avatar = req.avatar() == null ? existing.getAvatar() : blankToNull(req.avatar());
        String notice = req.notice() == null ? existing.getNotice() : blankToNull(req.notice());
        groupDao.updateInfo(id, name, avatar, notice);
        publishGroupsChanged();
        return ResponseEntity.ok(groupDao.findById(id));
    }

    /** 解散群聊：会一起删掉该群的消息、群共识、记忆进度、开关与 token 流水。示例群不允许解散。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable("id") String id) {
        GroupDto existing = groupDao.findById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (existing.isSample()) {
            return bad("示例群不允许解散");
        }
        // 先清 vec0 表里的群共识向量，再删关系数据（vec0 表不支持时该方法自身会跳过）
        for (Long rowid : memoryDao.sharedFactIds(id)) {
            vectorStore.deleteSharedFact(rowid);
        }
        groupDao.deleteGroupData(id);
        groupDao.delete(id);
        scheduler.dispose(id);
        publishGroupsChanged();
        return ResponseEntity.ok(Map.of("ok", true, "id", id));
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<List<AgentDef>> members(@PathVariable("id") String id) {
        if (!groupDao.exists(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(agentDao.findByGroup(id));
    }

    public record MembersRequest(List<String> agentIds) {
    }

    /** 拉人进群：写系统提示条并触发一轮，让其他人打招呼（B6）。 */
    @PostMapping("/{id}/members")
    public ResponseEntity<?> addMembers(@PathVariable("id") String id, @RequestBody MembersRequest req) {
        GroupDto group = groupDao.findById(id);
        if (group == null) {
            return ResponseEntity.notFound().build();
        }
        List<String> ids = validAgentIds(req == null ? null : req.agentIds());
        List<String> existing = groupDao.memberIds(id);
        List<String> fresh = new ArrayList<>();
        for (String agentId : ids) {
            if (!existing.contains(agentId)) {
                fresh.add(agentId);
            }
        }
        if (fresh.isEmpty()) {
            return bad("没有可加入的新成员");
        }
        fresh.forEach(a -> groupDao.addMember(id, a));
        MessageDto sys = saveSystemMessage(id, names(fresh) + "加入了群聊，大家来打个招呼吧～");
        scheduler.onHumanMessage(id, sys.getId());
        publishGroupsChanged();
        return ResponseEntity.ok(agentDao.findByGroup(id));
    }

    /** 把成员移出群：历史消息与个人印象保留（人设本身也还在，只是不在这个群里）。 */
    @DeleteMapping("/{id}/members/{agentId}")
    public ResponseEntity<?> removeMember(@PathVariable("id") String id, @PathVariable("agentId") String agentId) {
        if (!groupDao.exists(id)) {
            return ResponseEntity.notFound().build();
        }
        groupDao.removeMember(id, agentId);
        publishGroupsChanged();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ---- 内部 ----

    private ResponseEntity<Map<String, Object>> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    /** 过滤出真实存在且启用中的成员 id（去重、保持顺序）。 */
    private List<String> validAgentIds(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(requested));
        return agentDao.findByIds(ids).stream().filter(AgentDef::isActive).map(AgentDef::getId).toList();
    }

    private String names(List<String> agentIds) {
        StringBuilder sb = new StringBuilder();
        for (AgentDef a : agentDao.findByIds(agentIds)) {
            sb.append('「').append(a.getName()).append('」');
        }
        return sb.toString();
    }

    private MessageDto saveSystemMessage(String groupId, String content) {
        MessageDto m = new MessageDto();
        m.setGroupId(groupId);
        m.setSenderId("system");
        m.setSenderType(MessageDto.TYPE_SYSTEM);
        m.setSenderName("系统");
        m.setAvatar("📢");
        m.setContent(content);
        m.setCreatedAt(TimeUtil.now());
        long id = messageDao.insert(m);
        m.setId(id);
        sseHub.publish("system", m);
        return m;
    }

    private void publishGroupsChanged() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("changed", true);
        sseHub.publish("groups", data);
    }

    private static String trimName(String raw) {
        String s = raw.strip();
        return s.length() > MAX_NAME_LEN ? s.substring(0, MAX_NAME_LEN) : s;
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }
}