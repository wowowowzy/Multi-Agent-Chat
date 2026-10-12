package com.example.agentscopegroupchat.web;

import com.example.agentscopegroupchat.config.AppProperties;
import com.example.agentscopegroupchat.config.ModelConfigService;
import com.example.agentscopegroupchat.dto.ChatStateDto;
import com.example.agentscopegroupchat.dto.GroupDto;
import com.example.agentscopegroupchat.dto.ModelConfigDto;
import com.example.agentscopegroupchat.dto.ModelConfigRequest;
import com.example.agentscopegroupchat.event.SseHub;
import com.example.agentscopegroupchat.model.ModelProvider;
import com.example.agentscopegroupchat.store.AgentDao;
import com.example.agentscopegroupchat.store.ChatStateDao;
import com.example.agentscopegroupchat.store.GroupDao;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行期配置与元信息：每个群的聊天开关（C4）、前端所需的群/模型/记忆元信息。
 *
 * <p>多群（E1）：{@code groupId} 缺省回退到示例群（多群改造前的唯一群），
 * 所以老的 {@code /api/config/meta} 调用行为不变。
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final AppProperties props;
    private final ChatStateDao chatStateDao;
    private final GroupDao groupDao;
    private final AgentDao agentDao;
    private final ModelProvider modelProvider;
    private final ModelConfigService modelConfigService;
    private final SseHub sseHub;

    public ConfigController(AppProperties props, ChatStateDao chatStateDao, GroupDao groupDao, AgentDao agentDao,
            ModelProvider modelProvider, ModelConfigService modelConfigService, SseHub sseHub) {
        this.props = props;
        this.chatStateDao = chatStateDao;
        this.groupDao = groupDao;
        this.agentDao = agentDao;
        this.modelProvider = modelProvider;
        this.modelConfigService = modelConfigService;
        this.sseHub = sseHub;
    }

    @GetMapping("/chat-state")
    public ChatStateDto chatState(@RequestParam(name = "groupId", required = false) String groupId) {
        return chatStateDao.get(resolveGroup(groupId));
    }

    public record ChatStateRequest(String groupId, Boolean stopped) {
    }

    /** 停止/恢复聊天（C4）：停止后该群只存消息不触发决策。 */
    @PostMapping("/chat-state")
    public ChatStateDto setChatState(@RequestBody ChatStateRequest req) {
        String groupId = resolveGroup(req == null ? null : req.groupId());
        boolean stopped = req != null && Boolean.TRUE.equals(req.stopped());
        chatStateDao.setStopped(groupId, stopped);
        ChatStateDto dto = chatStateDao.get(groupId);
        sseHub.publish("state", dto);
        return dto;
    }

    /** 前端渲染所需元信息（群名、成员、模型是否配置、记忆是否启用、是否需要 token）。 */
    @GetMapping("/meta")
    public Map<String, Object> meta(@RequestParam(name = "groupId", required = false) String groupIdParam) {
        String groupId = resolveGroup(groupIdParam);
        GroupDto group = groupDao.findById(groupId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("groupId", groupId);
        m.put("groupName", group != null ? group.getName() : props.getChat().getGroupName());
        m.put("groupAvatar", group != null && group.getAvatar() != null ? group.getAvatar() : "💬");
        m.put("groupNotice", group != null ? group.getNotice() : null);
        m.put("isSample", group != null && group.isSample());
        m.put("memberCount", group != null ? group.getMemberCount() : 0);
        m.put("defaultGroupId", props.getChat().getGroupId());
        m.put("cascadeMaxDepth", props.getChat().getCascadeMaxDepth());
        m.put("speakQuotaPercent", props.getChat().getSpeakQuotaPercent());
        m.put("chatModelConfigured", modelProvider.isChatConfigured());
        m.put("gateModelConfigured", modelProvider.isGateConfigured());
        m.put("memoryEnabled", props.getMemory().isEnabled());
        m.put("vecDimension", props.getVec().getDimension());
        m.put("accessTokenRequired", props.getAccessToken() != null && !props.getAccessToken().isBlank());
        m.put("agentCount", agentDao.findActive().size());
        List<String> memberIds = groupDao.memberIds(groupId);
        m.put("memberIds", new ArrayList<>(memberIds));
        return m;
    }

    private String resolveGroup(String groupId) {
        return groupId == null || groupId.isBlank() ? props.getChat().getGroupId() : groupId;
    }

    // ---------- 模型配置（首启向导 / 管理台共用） ----------

    /** 当前模型配置（apiKey 已脱敏）+ .env 路径 + 被环境变量覆盖的键。 */
    @GetMapping("/model")
    public ModelConfigDto model() {
        return modelConfigService.current();
    }

    /** 保存模型配置：写 .env 并就地热更新（无需重启）。 */
    @PutMapping("/model")
    public ModelConfigDto updateModel(@RequestBody(required = false) ModelConfigRequest req) {
        ModelConfigDto dto = modelConfigService.apply(req);
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("chatModelConfigured", modelProvider.isChatConfigured());
        evt.put("gateModelConfigured", modelProvider.isGateConfigured());
        sseHub.publish("model", evt);
        return dto;
    }
}