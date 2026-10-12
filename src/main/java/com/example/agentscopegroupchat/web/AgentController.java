package com.example.agentscopegroupchat.web;

import com.example.agentscopegroupchat.agent.AgentRegistry;
import com.example.agentscopegroupchat.dto.AgentDef;
import com.example.agentscopegroupchat.store.AgentDao;
import com.example.agentscopegroupchat.util.TimeUtil;
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
 * Agent 管理（5.3）：列表 / 新增 / 编辑 / 逻辑删除 / 热重载（D3）。
 *
 * <p>人设 DB 为唯一真源，保存后调用 {@link AgentRegistry#reload()} 让下次决策使用最新人设与模型配置。
 *
 * <p>多群（E1）之后，新建人设**不会**自动进群：人设是全局的，进哪个群由
 * {@code POST /api/groups/{id}/members} 决定（"拉人进群"时才会写加入提示条并触发打招呼）。
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentDao agentDao;
    private final AgentRegistry agentRegistry;

    public AgentController(AgentDao agentDao, AgentRegistry agentRegistry) {
        this.agentDao = agentDao;
        this.agentRegistry = agentRegistry;
    }

    @GetMapping
    public List<AgentDef> list() {
        return agentDao.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<AgentDef> get(@PathVariable("id") String id) {
        AgentDef a = agentDao.findById(id);
        return a == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(a);
    }

    public record SaveRequest(String name, String avatar, String persona, Integer verbosity,
            Integer emojiTendency, Integer responseSpeed, Integer eagernessBias, String relationships,
            Boolean active) {
    }

    @PostMapping
    public ResponseEntity<AgentDef> create(@RequestBody SaveRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        AgentDef a = new AgentDef();
        a.setId("ag-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        apply(a, req);
        a.setActive(req.active() == null || req.active());
        String now = TimeUtil.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        agentDao.insert(a);
        agentRegistry.reload();
        return ResponseEntity.ok(a);
    }

    @PutMapping("/{id}")
    public ResponseEntity<AgentDef> update(@PathVariable("id") String id, @RequestBody SaveRequest req) {
        AgentDef existing = agentDao.findById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (req == null) {
            return ResponseEntity.badRequest().build();
        }
        apply(existing, req);
        if (req.active() != null) {
            existing.setActive(req.active());
        }
        existing.setId(id);
        agentDao.update(existing);
        agentRegistry.reload();
        return ResponseEntity.ok(agentDao.findById(id));
    }

    /** 逻辑删除（停用）：历史消息与个人印象保留。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable("id") String id) {
        AgentDef existing = agentDao.findById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        agentDao.deactivate(id);
        agentRegistry.reload();
        return ResponseEntity.ok(Map.of("id", id, "active", false));
    }

    /** 人设热重载（D3）：重置模型/人设缓存，下次决策生效。 */
    @PostMapping("/reload")
    public Map<String, Object> reload() {
        agentRegistry.reload();
        return Map.of("ok", true, "message", "已热重载：下次决策将使用最新人设与模型配置");
    }

    // ---- 内部 ----

    private void apply(AgentDef a, SaveRequest req) {
        if (req.name() != null && !req.name().isBlank()) {
            a.setName(req.name().trim());
        }
        if (req.avatar() != null) {
            a.setAvatar(req.avatar());
        }
        if (req.persona() != null) {
            a.setPersona(req.persona());
        }
        if (req.verbosity() != null) {
            a.setVerbosity(clamp(req.verbosity()));
        }
        if (req.emojiTendency() != null) {
            a.setEmojiTendency(clamp(req.emojiTendency()));
        }
        if (req.responseSpeed() != null) {
            a.setResponseSpeed(clamp(req.responseSpeed()));
        }
        if (req.eagernessBias() != null) {
            a.setEagernessBias(clamp(req.eagernessBias()));
        }
        if (req.relationships() != null) {
            a.setRelationships(req.relationships());
        }
        a.setUpdatedAt(TimeUtil.now());
    }

    private static int clamp(Integer v) {
        return Math.max(1, Math.min(10, v));
    }
}