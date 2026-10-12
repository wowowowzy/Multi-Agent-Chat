package com.example.agentscopegroupchat.model;

import com.example.agentscopegroupchat.config.AppProperties;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.OpenAIMultiAgentFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 统一 OpenAI 兼容模型构建（chat 主力 / gate flash 级）。
 *
 * <p>配置留空时返回 null，由调度器降级为友好提示，不阻断应用启动——方便先跑通骨架、
 * 之后再填 application.yml 的 api-key/base-url/model。
 */
@Component
public class ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(ModelProvider.class);

    private final AppProperties props;

    private volatile Model chatModel;
    private volatile Model gateModel;
    private volatile boolean chatResolved;
    private volatile boolean gateResolved;

    public ModelProvider(AppProperties props) {
        this.props = props;
    }

    public boolean isChatConfigured() {
        return props.getModel().getChat().isConfigured();
    }

    public boolean isGateConfigured() {
        return props.getModel().getGate().isConfigured() || isChatConfigured();
    }

    /** 主力发言模型（qwen-max / gpt-4.1 等）。 */
    public Model chat() {
        if (!chatResolved) {
            synchronized (this) {
                if (!chatResolved) {
                    chatModel = build(props.getModel().getChat(), true, "chat");
                    chatResolved = true;
                }
            }
        }
        return chatModel;
    }

    /** 门控模型（flash 级）；未单独配置时回退到主力模型。 */
    public Model gate() {
        if (!gateResolved) {
            synchronized (this) {
                if (!gateResolved) {
                    AppProperties.Endpoint ep = props.getModel().getGate();
                    if (ep.isConfigured()) {
                        gateModel = build(ep, false, "gate");
                    } else {
                        gateModel = chat();
                        log.info("gate 模型未单独配置，回退使用 chat 模型");
                    }
                    gateResolved = true;
                }
            }
        }
        return gateModel;
    }

    /** 配置变更后重置缓存（热重载预留）。 */
    public void reset() {
        chatResolved = false;
        gateResolved = false;
    }

    private Model build(AppProperties.Endpoint ep, boolean multiAgent, String tag) {
        if (!ep.isConfigured()) {
            log.warn("{} 模型未配置（model 为空），相关功能将不可用", tag);
            return null;
        }
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(ep.getApiKey() == null ? "" : ep.getApiKey())
                .modelName(ep.getModel())
                .stream(true);
        if (StringUtils.hasText(ep.getBaseUrl())) {
            builder.baseUrl(ep.getBaseUrl().trim());
        }
        if (ep.isDisableThinking()) {
            // 思考模式下网关拒绝 tool_choice=required，会直接打断结构化输出，故关闭思考。
            builder.generateOptions(GenerateOptions.builder()
                    .additionalBodyParam("enable_thinking", false)
                    .build());
        }
        // 关闭"原生结构化输出"（response_format=json_schema）：本网关不支持该类型，
        // 否则每轮都会先发一次注定 400 的请求再回退。关掉后 AgentScope 直接走
        // "合成 tool" 回退路径（tool_choice=required），一条请求拿到结构化结果。
        builder.nativeStructuredOutput(false);
        if (multiAgent) {
            // 多 agent 场景专用 formatter：自动合并多方消息、用 sender 名区分身份
            builder.formatter(new OpenAIMultiAgentFormatter());
        }
        log.info("构建 {} 模型：{} @ {}", tag, ep.getModel(),
                StringUtils.hasText(ep.getBaseUrl()) ? ep.getBaseUrl() : "(默认端点)");
        return builder.build();
    }
}