package com.example.agentscopegroupchat.agent;

import com.example.agentscopegroupchat.config.AppProperties;
import com.example.agentscopegroupchat.dto.AgentDef;
import com.example.agentscopegroupchat.holiday.HolidayCalendar;
import com.example.agentscopegroupchat.model.ModelProvider;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Agent 工厂 / 注册中心（5.2 agent 包）。
 *
 * <p>人设来自 DB，每次调用即时构建 ReActAgent 实例（无状态投喂，见 4.3），
 * 因此"人设热重载"天然生效（D3）：改完 DB 下次决策即使用新实例。
 * 模型构建带缓存，热重载时通过 {@link #reload()} 重置。
 */
@Component
public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    private final ModelProvider modelProvider;
    private final AppProperties props;
    private final HolidayCalendar holidayCalendar;

    public AgentRegistry(ModelProvider modelProvider, AppProperties props, HolidayCalendar holidayCalendar) {
        this.modelProvider = modelProvider;
        this.props = props;
        this.holidayCalendar = holidayCalendar;
    }

    /** 构建发言 agent（主力模型 + 人设系统提示）。模型未配置返回 null。 */
    public ReActAgent newChatAgent(AgentDef def, String groupName) {
        return newChatAgent(def, groupName, null);
    }

    /** 带群公告的版本（多群：每个群有自己的名字与公告）。 */
    public ReActAgent newChatAgent(AgentDef def, String groupName, String groupNotice) {
        Model model = modelProvider.chat();
        if (model == null) {
            return null;
        }
        return ReActAgent.builder()
                .name(def.getName())
                .sysPrompt(Prompts.systemPrompt(def, groupName, groupNotice, holidayCalendar.nowLine()))
                .model(model)
                .maxIters(2)
                // 发言不限制输出格式，仅限制长度，避免个别 agent 失控刷屏
                .generateOptions(GenerateOptions.builder()
                        .maxTokens(props.getChat().getSpeakMaxTokens())
                        .build())
                .build();
    }

    /** 构建门控 agent（flash 级模型 + 通用门控提示）。模型未配置返回 null。 */
    public ReActAgent newGateAgent() {
        Model model = modelProvider.gate();
        if (model == null) {
            return null;
        }
        return ReActAgent.builder()
                .name("gate")
                .sysPrompt(Prompts.GATE_SYS)
                .model(model)
                .maxIters(3)
                // 门控要的是结构化 JSON：
                //  - maxTokens 给足余量，防止 JSON 被截断；
                //  - responseFormat=json_object 作为兜底：万一模型没走"合成 tool"路径，
                //    返回的 content 也保证是合法 JSON，交回 GroupScheduler 的宽松解析。
                .generateOptions(GenerateOptions.builder()
                        .maxTokens(props.getChat().getGateMaxTokens())
                        .responseFormat(ResponseFormat.jsonObject())
                        .build())
                .build();
    }

    /** 人设 / 模型热重载（D3）。 */
    public void reload() {
        modelProvider.reset();
        log.info("Agent 热重载：模型缓存已重置，下次决策将使用最新人设与模型配置");
    }
}