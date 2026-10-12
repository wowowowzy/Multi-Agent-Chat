package com.example.agentscopegroupchat.config;

import com.example.agentscopegroupchat.agent.AgentRegistry;
import com.example.agentscopegroupchat.dto.ModelConfigDto;
import com.example.agentscopegroupchat.dto.ModelConfigRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 模型配置的读写与热更新（首启向导 + 管理台"模型配置"页共用同一套逻辑）。
 *
 * <p><b>真源仍是 {@code .env}</b>（开源仓不能把密钥写进 yml）。这里做两件事：
 *
 * <ol>
 *   <li><b>落盘</b>：就地改写 .env 里受管的那几行（保留其它行、注释、用户自己加的环境变量），
 *       不整文件覆盖，避免把用户手写的配置抹掉；</li>
 *   <li><b>热更新</b>：同时把新值写进运行中的 {@link AppProperties} 实例。
 *       由于 {@code ModelProvider} 缓存的是 Model 对象、{@code EmbeddingClient}/{@code RerankClient}
 *       持有的是 Endpoint 引用，"就地改字段 + 重置模型缓存"即可让新配置立即生效，
 *       不需要重启进程。</li>
 * </ol>
 *
 * <p>注意：写 .env 本身<b>不会</b>被 Spring 重读（{@code DotenvEnvironmentPostProcessor} 只在启动时执行），
 * 所以"写盘"负责重启后仍生效，"改内存"负责当前进程立即生效，两者必须一起做。
 *
 * <p>唯一无法热更新的是向量维度：vec0 虚拟表的维度在启动建表时就固定了，改维度需要重启
 * （且已有记忆向量与新区间不兼容），故仅落盘并给出提醒。
 */
@Service
public class ModelConfigService {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigService.class);

    /** 用途 → .env 键前缀。顺序即 UI 展示顺序。 */
    private static final List<String[]> GROUPS = List.of(
            new String[]{"chat", "APP_MODEL_CHAT"},
            new String[]{"gate", "APP_MODEL_GATE"},
            new String[]{"embedding", "APP_MODEL_EMBEDDING"},
            new String[]{"rerank", "APP_MODEL_RERANK"});

    /** 受管的键后缀（与 application.yml 的 app.model.<用途>.<字段> 对齐）。 */
    private static final List<String> SUFFIXES =
            List.of("BASE_URL", "API_KEY", "MODEL", "DISABLE_THINKING");

    private static final String VEC_DIM_KEY = "APP_VEC_DIMENSION";

    private final AppProperties props;
    private final AgentRegistry agentRegistry;
    private final Environment environment;

    public ModelConfigService(AppProperties props, AgentRegistry agentRegistry, Environment environment) {
        this.props = props;
        this.agentRegistry = agentRegistry;
        this.environment = environment;
    }

    // ---------- 读 ----------

    public ModelConfigDto current() {
        return current(List.of());
    }

    private ModelConfigDto current(List<String> warnings) {
        Path file = resolveEnvPath();
        return new ModelConfigDto(
                detail("chat"), detail("gate"), detail("embedding"), detail("rerank"),
                props.getVec().getDimension(),
                file.toString(),
                Files.isRegularFile(file),
                envOverrideKeys(),
                List.copyOf(warnings));
    }

    private ModelConfigDto.EndpointDetail detail(String name) {
        AppProperties.Endpoint ep = endpointOf(name);
        boolean apiKeySet = StringUtils.hasText(ep.getApiKey());
        return new ModelConfigDto.EndpointDetail(
                nullToEmpty(ep.getBaseUrl()),
                nullToEmpty(ep.getModel()),
                apiKeySet ? mask(ep.getApiKey()) : "",
                apiKeySet,
                ep.isDisableThinking(),
                ep.isConfigured());
    }

    /** 掩码：只留头 4 位与尾 4 位，中间固定 4 个星号（够用户确认"是我那把 key"）。 */
    private static String mask(String key) {
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    /** 哪些键被真实环境变量占住了：这些项即使写进 .env，重启后也会被环境变量顶掉。 */
    private List<String> envOverrideKeys() {
        List<String> out = new ArrayList<>();
        for (String[] group : GROUPS) {
            for (String suffix : SUFFIXES) {
                String key = group[1] + "_" + suffix;
                if (System.getenv(key) != null) {
                    out.add(key);
                }
            }
        }
        if (System.getenv(VEC_DIM_KEY) != null) {
            out.add(VEC_DIM_KEY);
        }
        return out;
    }

    // ---------- 写 ----------

    /**
     * 保存模型配置：先改内存（立即生效），再写 .env（重启后仍生效）。
     *
     * @throws IllegalArgumentException 参数非法（前端会直接展示 message）
     */
    public ModelConfigDto apply(ModelConfigRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("请求体为空");
        }
        Map<String, String> toWrite = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        applyOne("chat", req.chat(), toWrite);
        applyOne("gate", req.gate(), toWrite);
        applyOne("embedding", req.embedding(), toWrite);
        applyOne("rerank", req.rerank(), toWrite);

        if (req.vecDimension() != null) {
            int dim = req.vecDimension();
            if (dim <= 0 || dim > 20000) {
                throw new IllegalArgumentException("向量维度必须在 1~20000 之间");
            }
            if (dim != props.getVec().getDimension()) {
                props.getVec().setDimension(dim);
                toWrite.put(VEC_DIM_KEY, String.valueOf(dim));
                warnings.add("向量维度已改为 " + dim + "，需要重启应用才生效；"
                        + "若库里已有旧维度的记忆向量，建议清空记忆表后重新积累（新旧维度不兼容）。");
            }
        }

        if (toWrite.isEmpty()) {
            return current(warnings);
        }
        writeEnv(toWrite);
        // 就地改完 AppProperties 后重置模型缓存：下次决策即用新配置（无需重启）。
        agentRegistry.reload();
        log.info("模型配置已更新：{} 项写入 {}（已热更新）", toWrite.size(), resolveEnvPath());
        return current(warnings);
    }

    private void applyOne(String name, ModelConfigRequest.EndpointInput in, Map<String, String> toWrite) {
        if (in == null) {
            return;
        }
        String prefix = prefixOf(name);

        if (in.baseUrl() != null) {
            String baseUrl = in.baseUrl().trim();
            if (StringUtils.hasText(baseUrl) && !isHttpUrl(baseUrl)) {
                throw new IllegalArgumentException(label(name) + "的 baseUrl 必须以 http:// 或 https:// 开头");
            }
            endpointOf(name).setBaseUrl(baseUrl);
            toWrite.put(prefix + "_BASE_URL", baseUrl);
        }
        if (in.model() != null) {
            String model = in.model().trim();
            endpointOf(name).setModel(model);
            toWrite.put(prefix + "_MODEL", model);
        }
        if (in.disableThinking() != null) {
            endpointOf(name).setDisableThinking(in.disableThinking());
            toWrite.put(prefix + "_DISABLE_THINKING", String.valueOf(in.disableThinking()));
        }

        // apiKey 三态：清空 / 覆盖 / 不动
        if (Boolean.TRUE.equals(in.clearApiKey())) {
            endpointOf(name).setApiKey("");
            toWrite.put(prefix + "_API_KEY", "");
        } else if (in.apiKey() != null && !in.apiKey().isBlank()) {
            String key = in.apiKey().trim();
            endpointOf(name).setApiKey(key);
            toWrite.put(prefix + "_API_KEY", key);
        }

        AppProperties.Endpoint ep = endpointOf(name);
        if (StringUtils.hasText(ep.getBaseUrl()) && !StringUtils.hasText(ep.getModel())) {
            throw new IllegalArgumentException(label(name) + "填了 baseUrl 就必须填模型名（model）");
        }
        if (ep.isConfigured() && !StringUtils.hasText(ep.getBaseUrl())) {
            // 不是错误：embedding/rerank 有些实现有默认端点。仅提示一次，避免用户以为没生效。
            log.debug("{} 只配了 model、没配 baseUrl，将使用实现内置默认端点", name);
        }
    }

    /** 就地改写受管的行；其余内容（注释、用户自加变量）保持不动。 */
    private void writeEnv(Map<String, String> updates) {
        Path file = resolveEnvPath();
        List<String> lines = new ArrayList<>();
        if (Files.isRegularFile(file)) {
            try {
                lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException("读取 .env 失败：" + file, e);
            }
        }
        for (Map.Entry<String, String> e : updates.entrySet()) {
            String line = e.getKey() + "=" + quoteIfNeeded(e.getValue());
            int idx = indexOfKey(lines, e.getKey());
            if (idx >= 0) {
                lines.set(idx, line);
            } else {
                lines.add(line);
            }
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // 显式 UTF-8 写出（无 BOM），与 DotenvEnvironmentPostProcessor 的读取编码一致
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入 .env 失败：" + file, e);
        }
    }

    /** 找 KEY=... 或 export KEY=... 所在行（注释行不匹配）。 */
    private static int indexOfKey(List<String> lines, String key) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i) == null ? "" : lines.get(i).trim();
            if (line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).trim();
            }
            int eq = line.indexOf('=');
            if (eq > 0 && line.substring(0, eq).trim().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /** 值里含 # 或首尾空格或引号时必须加引号，否则会被解析成行尾注释/被截断。 */
    private static String quoteIfNeeded(String value) {
        String v = value == null ? "" : value;
        if (v.isEmpty()) {
            return "";
        }
        boolean needQuote = v.indexOf('#') >= 0
                || v.indexOf('"') >= 0
                || v.indexOf('\\') >= 0
                || !v.equals(v.trim());
        if (!needQuote) {
            return v;
        }
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * .env 的真实路径：优先用启动时实际加载的那一个（可能是向上找到的工程根目录），
     * 没找到文件时退回"当前工作目录/.env"（首启向导场景）。
     */
    public Path resolveEnvPath() {
        String loaded = environment.getProperty(DotenvEnvironmentPostProcessor.LOADED_PATH_KEY);
        if (StringUtils.hasText(loaded)) {
            return Paths.get(loaded).toAbsolutePath();
        }
        return Paths.get("").toAbsolutePath().resolve(".env");
    }

    private AppProperties.Endpoint endpointOf(String name) {
        return switch (name) {
            case "chat" -> props.getModel().getChat();
            case "gate" -> props.getModel().getGate();
            case "embedding" -> props.getModel().getEmbedding();
            case "rerank" -> props.getModel().getRerank();
            default -> throw new IllegalArgumentException("未知的模型用途：" + name);
        };
    }

    private static String prefixOf(String name) {
        for (String[] group : GROUPS) {
            if (group[0].equals(name)) {
                return group[1];
            }
        }
        throw new IllegalArgumentException("未知的模型用途：" + name);
    }

    private static String label(String name) {
        return switch (name) {
            case "chat" -> "主力发言模型";
            case "gate" -> "门控模型";
            case "embedding" -> "向量模型";
            case "rerank" -> "重排模型";
            default -> name;
        };
    }

    private static boolean isHttpUrl(String s) {
        return s.startsWith("http://") || s.startsWith("https://");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}