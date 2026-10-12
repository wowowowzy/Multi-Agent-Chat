package com.example.agentscopegroupchat.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 配置契约测试：真实的 {@code application.yml} 占位符必须真的把 {@code .env} 的键接上。
 *
 * <p><b>为什么需要这个测试：</b>{@code disable-thinking} 曾经写成字面量 {@code true} 而不是
 * {@code ${APP_MODEL_CHAT_DISABLE_THINKING:true}}，于是"关闭思考模式"这个开关无论写进 {@code .env}
 * 还是从管理台改，都<b>静默失效</b>——配置看起来生效了、实际没接上。
 * 这类"写了但没接上"的坑，只能靠"真 yml + 真解析器 + 真绑定"来防。
 */
class AppPropertiesBindingTest {

    /** 用与运行期一致的链路绑定：真实 application.yml + DotenvEnvironmentPostProcessor 解析出的 .env。 */
    private static AppProperties bind(List<String> envLines) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        // .env 优先级高于 yml（与运行期一致）：占位符就是从这里取到值的
        sources.addFirst(new MapPropertySource("dotenvFile", DotenvEnvironmentPostProcessor.parse(envLines)));
        sources.addLast(yaml());

        Binder binder = new Binder(
                ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
        return binder.bind("app", Bindable.of(AppProperties.class)).get();
    }

    private static PropertySource<?> yaml() throws IOException {
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        assertFalse(loaded.isEmpty(), "application.yml 必须能被加载");
        return loaded.get(0);
    }

    /** 四个用途的模型端点都要能从 .env 的 APP_MODEL_* 键取到值。 */
    @Test
    void modelEndpointFieldsAreWiredFromEnv() throws IOException {
        AppProperties p = bind(List.of(
                "APP_MODEL_CHAT_BASE_URL=https://api.deepseek.com/v1",
                "APP_MODEL_CHAT_API_KEY=sk-abc",
                "APP_MODEL_CHAT_MODEL=deepseek-chat",
                "APP_MODEL_RERANK_BASE_URL=https://api.siliconflow.cn/v1/rerank",
                "APP_MODEL_RERANK_MODEL=BAAI/bge-reranker-v2-m3",
                "APP_VEC_DIMENSION=1536"));

        assertEquals("https://api.deepseek.com/v1", p.getModel().getChat().getBaseUrl());
        assertEquals("sk-abc", p.getModel().getChat().getApiKey());
        assertEquals("deepseek-chat", p.getModel().getChat().getModel());
        assertEquals("https://api.siliconflow.cn/v1/rerank", p.getModel().getRerank().getBaseUrl());
        assertEquals(1536, p.getVec().getDimension());
        assertTrue(p.getModel().getChat().isConfigured());
        assertFalse(p.getModel().getGate().isConfigured(), "gate 留空 = 未配置，由调度器回退到 chat");
    }

    /** 核心回归点：disable-thinking 必须由 APP_MODEL_*_DISABLE_THINKING 决定。 */
    @Test
    void disableThinkingIsWiredFromEnvForAllFourEndpoints() throws IOException {
        AppProperties p = bind(List.of(
                "APP_MODEL_CHAT_DISABLE_THINKING=false",
                "APP_MODEL_GATE_DISABLE_THINKING=false",
                "APP_MODEL_EMBEDDING_DISABLE_THINKING=false",
                "APP_MODEL_RERANK_DISABLE_THINKING=false"));

        assertFalse(p.getModel().getChat().isDisableThinking(), "chat.disable-thinking 没接上 .env（曾经就是这里静默失效）");
        assertFalse(p.getModel().getGate().isDisableThinking());
        assertFalse(p.getModel().getEmbedding().isDisableThinking());
        assertFalse(p.getModel().getRerank().isDisableThinking());
    }

    /** 不写任何键时，四个端点都默认关闭思考（百炼/MaaS 需要它的默认行为）。 */
    @Test
    void disableThinkingDefaultsToTrueEverywhere() throws IOException {
        AppProperties p = bind(List.of());

        assertTrue(p.getModel().getChat().isDisableThinking());
        assertTrue(p.getModel().getGate().isDisableThinking());
        assertTrue(p.getModel().getEmbedding().isDisableThinking());
        assertTrue(p.getModel().getRerank().isDisableThinking());
    }

    /** 真值也要能覆盖（有人会显式写 true）。 */
    @Test
    void disableThinkingTrueFromEnvIsHonoured() throws IOException {
        AppProperties p = bind(List.of("APP_MODEL_CHAT_DISABLE_THINKING=true"));
        assertTrue(p.getModel().getChat().isDisableThinking());
    }

    /** 节假日接口的开关/地址/超时也要真的从 .env 接上（同 disable-thinking，写错就静默失效）。 */
    @Test
    void holidaySettingsAreWiredFromEnv() throws IOException {
        AppProperties p = bind(List.of(
                "APP_HOLIDAY_ENABLED=false",
                "APP_HOLIDAY_BASE_URL=https://example.com/holidays",
                "APP_HOLIDAY_TIMEOUT_MS=800",
                "APP_HOLIDAY_RETRY_SECONDS=120"));

        assertFalse(p.getHoliday().isEnabled());
        assertEquals("https://example.com/holidays", p.getHoliday().getBaseUrl());
        assertEquals(800, p.getHoliday().getTimeoutMs());
        assertEquals(120, p.getHoliday().getRetrySeconds());
    }

    /** 不配置时默认开启、指向公共接口（查不到会自动降级，所以默认开启是安全的）。 */
    @Test
    void holidayDefaultsAreSane() throws IOException {
        AppProperties p = bind(List.of());
        assertTrue(p.getHoliday().isEnabled());
        assertEquals("https://api.apisbo.com/holidays/date", p.getHoliday().getBaseUrl());
        assertEquals(2500, p.getHoliday().getTimeoutMs());
        assertEquals(600, p.getHoliday().getRetrySeconds());
    }

    /** yml 自己的默认值不能因为这次改动被打乱。 */
    @Test
    void ymlDefaultsSurviveWhenEnvIsAbsent() throws IOException {
        AppProperties p = bind(List.of());

        assertEquals(1024, p.getVec().getDimension());
        assertEquals(60, p.getChat().getIdleTopicTriggerSec());
        assertEquals(3, p.getChat().getMaxIdleRoundsWithoutHuman());
        assertEquals(50, p.getChat().getSpeakQuotaPercent());
        assertEquals("data/chat.db", p.getDbPath());
        assertFalse(p.getModel().getChat().isConfigured());
        assertEquals("", p.getAccessToken());
    }
}