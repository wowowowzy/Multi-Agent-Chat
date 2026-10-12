package com.example.agentscopegroupchat.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.agentscopegroupchat.agent.AgentRegistry;
import com.example.agentscopegroupchat.dto.ModelConfigDto;
import com.example.agentscopegroupchat.dto.ModelConfigRequest;
import com.example.agentscopegroupchat.holiday.HolidayCalendar;
import com.example.agentscopegroupchat.model.ModelProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/**
 * 模型配置读写测试：.env 只改受管行（不整文件覆盖）、apiKey 三态语义、以及"就地热更新"。
 */
class ModelConfigServiceTest {

    @TempDir
    Path dir;

    private Path envFile;
    private AppProperties props;
    private ModelConfigService service;

    @BeforeEach
    void setUp() throws IOException {
        envFile = dir.resolve(".env");
        props = new AppProperties();
        MockEnvironment env = new MockEnvironment();
        env.setProperty(DotenvEnvironmentPostProcessor.LOADED_PATH_KEY, envFile.toString());
        ModelProvider modelProvider = new ModelProvider(props);
        service = new ModelConfigService(props,
                new AgentRegistry(modelProvider, props, new HolidayCalendar(props, new ObjectMapper())), env);
    }

    private List<String> lines() throws IOException {
        return Files.readAllLines(envFile, StandardCharsets.UTF_8);
    }

    private long countKey(String key) throws IOException {
        return lines().stream()
                .map(String::trim)
                .filter(l -> l.startsWith(key + "="))
                .count();
    }

    private ModelConfigRequest.EndpointInput chat(String baseUrl, String apiKey, String model) {
        return new ModelConfigRequest.EndpointInput(baseUrl, apiKey, null, model, null);
    }

    @Test
    void createsEnvFileWhenMissingAndWritesManagedKeys() throws IOException {
        assertFalse(Files.exists(envFile));

        ModelConfigDto dto = service.apply(new ModelConfigRequest(
                chat("https://api.deepseek.com/v1", "sk-abcdefgh1234", "deepseek-chat"),
                null, null, null, null));

        assertTrue(Files.exists(envFile), ".env 应被创建");
        assertTrue(lines().contains("APP_MODEL_CHAT_BASE_URL=https://api.deepseek.com/v1"));
        assertTrue(lines().contains("APP_MODEL_CHAT_MODEL=deepseek-chat"));
        assertTrue(lines().contains("APP_MODEL_CHAT_API_KEY=sk-abcdefgh1234"));
        assertTrue(dto.chat().configured());
        // 未提交的用途不应被写进文件
        assertEquals(0, countKey("APP_MODEL_GATE_MODEL"));
    }

    @Test
    void keepsUnmanagedLinesAndReplacesManagedOnesWithoutDuplicating() throws IOException {
        Files.write(envFile, List.of(
                "# 我自己加的注释",
                "APP_DB_PATH=data/chat.db",
                "APP_MODEL_CHAT_MODEL=旧模型",
                "",
                "MY_OWN_VAR=keep-me"), StandardCharsets.UTF_8);

        service.apply(new ModelConfigRequest(
                chat("https://api.deepseek.com/v1", null, "deepseek-chat"), null, null, null, null));

        List<String> out = lines();
        assertTrue(out.contains("# 我自己加的注释"), "用户注释必须保留");
        assertTrue(out.contains("APP_DB_PATH=data/chat.db"), "非受管键必须保留");
        assertTrue(out.contains("MY_OWN_VAR=keep-me"), "用户自加变量必须保留");
        assertTrue(out.contains("APP_MODEL_CHAT_MODEL=deepseek-chat"), "受管行应被就地替换");
        assertFalse(out.contains("APP_MODEL_CHAT_MODEL=旧模型"));
        assertEquals(1, countKey("APP_MODEL_CHAT_MODEL"), "不应出现重复键");
    }

    @Test
    void keepsExistingCommentsOnTheSameLineAreaAndQuotesTrickyValues() throws IOException {
        service.apply(new ModelConfigRequest(
                chat("https://x.example/v1", null, "m#1"), null, null, null, null));

        assertTrue(lines().contains("APP_MODEL_CHAT_MODEL=\"m#1\""),
                "含 # 的值必须加引号，否则会被当行尾注释截断");
    }

    @Test
    void apiKeyIsMaskedAndThreeStateSemanticsHold() throws IOException {
        service.apply(new ModelConfigRequest(
                chat("https://x.example/v1", "sk-1234567890abcd", "m"), null, null, null, null));

        ModelConfigDto first = service.current();
        assertEquals("sk-1****abcd", first.chat().apiKeyMasked());
        assertTrue(first.chat().apiKeySet());

        // apiKey=null：保持原值（掩码被当成真值写回去是最典型的坑）
        service.apply(new ModelConfigRequest(
                chat("https://x.example/v1", null, "m2"), null, null, null, null));
        assertEquals("sk-1234567890abcd", props.getModel().getChat().getApiKey());
        assertTrue(lines().contains("APP_MODEL_CHAT_API_KEY=sk-1234567890abcd"));

        // clearApiKey=true：清空
        service.apply(new ModelConfigRequest(
                new ModelConfigRequest.EndpointInput(null, null, true, null, null), null, null, null, null));
        assertEquals("", props.getModel().getChat().getApiKey());
        assertFalse(service.current().chat().apiKeySet());
        assertTrue(lines().contains("APP_MODEL_CHAT_API_KEY="), "清空应写成空值而不是删行");
    }

    @Test
    void rejectsBadBaseUrlAndModelWithoutBaseUrl() {
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class, () -> service.apply(
                new ModelConfigRequest(chat("api.deepseek.com", "k", "m"), null, null, null, null)));
        assertTrue(e1.getMessage().contains("baseUrl"));

        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class, () -> service.apply(
                new ModelConfigRequest(chat("https://x.example/v1", "k", ""), null, null, null, null)));
        assertTrue(e2.getMessage().contains("模型名"));
    }

    @Test
    void mutationHappensInPlaceSoHotReloadSeesIt() {
        // EmbeddingClient/RerankClient 在构造时持有 Endpoint 引用；必须是同一实例被改字段，
        // 否则换了配置它们仍读旧值（这正是"热更新"能成立的前提）。
        AppProperties.Endpoint before = props.getModel().getEmbedding();
        service.apply(new ModelConfigRequest(null, null,
                new ModelConfigRequest.EndpointInput("https://x.example/v1", "sk-emb", null,
                        "text-embedding-3-small", Boolean.FALSE),
                null, null));

        assertTrue(before == props.getModel().getEmbedding(), "Endpoint 实例必须被就地复用");
        assertEquals("sk-emb", before.getApiKey());
        assertEquals("text-embedding-3-small", before.getModel());
        assertFalse(before.isDisableThinking());
    }

    @Test
    void vecDimensionChangeOnlyWarnsAndNeverFails() {
        ModelConfigDto dto = service.apply(new ModelConfigRequest(null, null, null, null, 768));

        assertEquals(768, props.getVec().getDimension());
        assertFalse(dto.warnings().isEmpty(), "改维度需要重启，必须给出提醒");
        assertTrue(dto.warnings().get(0).contains("重启"));
    }

    @Test
    void noOpRequestDoesNotCreateFile() {
        ModelConfigDto dto = service.apply(new ModelConfigRequest(null, null, null, null, null));
        assertFalse(Files.exists(envFile), "没有任何改动时不应创建/改写 .env");
        assertFalse(dto.chat().configured());
    }

    @Test
    void currentWorksBeforeAnythingIsConfigured() {
        // 首启向导依赖"什么都没配时也能读到 .env 路径与空表单"
        ModelConfigDto dto = service.current();
        assertEquals(envFile.toString(), dto.envPath());
        assertFalse(dto.envFileExists());
        assertEquals("", dto.chat().apiKeyMasked());
        assertFalse(dto.chat().configured());
    }
}