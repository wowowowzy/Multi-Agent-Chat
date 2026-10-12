package com.example.agentscopegroupchat.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** .env 解析单测：开源场景下用户会写出各种 .env 写法，必须都认。 */
class DotenvEnvironmentPostProcessorTest {

    @Test
    void plainKeyValue() {
        Map<String, Object> m = DotenvEnvironmentPostProcessor.parse(List.of("APP_DB_PATH=data/chat.db"));
        assertEquals("data/chat.db", m.get("APP_DB_PATH"));
    }

    /** 双引号必须被剥掉 —— 否则 api-key 会带着引号发出去，鉴权静默失败。 */
    @Test
    void doubleQuotedValueLosesQuotes() {
        Map<String, Object> m = DotenvEnvironmentPostProcessor.parse(List.of("APP_MODEL_CHAT_API_KEY=\"sk-abc\"")); 
        assertEquals("sk-abc", m.get("APP_MODEL_CHAT_API_KEY"));
    }

    @Test
    void singleQuotedValueLosesQuotes() {
        Map<String, Object> m = DotenvEnvironmentPostProcessor.parse(List.of("K='sk-abc'"));
        assertEquals("sk-abc", m.get("K"));
    }

    @Test
    void exportPrefixIsAccepted() {
        Map<String, Object> m = DotenvEnvironmentPostProcessor.parse(List.of("export K=v"));
        assertEquals("v", m.get("K"));
    }

    @Test
    void commentsAndBlankLinesAreSkipped() {
        Map<String, Object> m = DotenvEnvironmentPostProcessor.parse(List.of(
                "# 这是注释", "", "   ", "K=v", "! 感叹号注释也行？不，跳过非法行"));
        assertEquals(1, m.size());
        assertEquals("v", m.get("K"));
    }

    @Test
    void trailingCommentIsStrippedForUnquotedValue() {
        Map<String, Object> m = DotenvEnvironmentPostProcessor.parse(List.of("K=v # 说明"));
        assertEquals("v", m.get("K"));
    }

    @Test
    void hashInsideQuotesIsKept() {
        Map<String, Object> m = DotenvEnvironmentPostProcessor.parse(List.of("K=\"a # b\""));
        assertEquals("a # b", m.get("K"));
    }

    /** URL 里的 # 不能被当注释切掉（不能被误伤）。 */
    @Test
    void hashWithoutPrecedingSpaceIsKept() {
        Map<String, Object> m = DotenvEnvironmentPostProcessor.parse(List.of("K=https://x/v1#anchor"));
        assertEquals("https://x/v1#anchor", m.get("K"));
    }

    /** 空值代表"显式留空"，键必须存在，否则 model 配置回退逻辑会看不到。 */
    @Test
    void emptyValueIsKept() {
        Map<String, Object> m = DotenvEnvironmentPostProcessor.parse(List.of("APP_ACCESS_TOKEN=", "K=   "));
        assertEquals("", m.get("APP_ACCESS_TOKEN"));
        assertEquals("", m.get("K"));
    }

    /** 值里可以再出现 =（base64、查询串、rerank 完整 URL 都可能带）。 */
    @Test
    void valueMayContainEqualsSign() {
        Map<String, Object> m = DotenvEnvironmentPostProcessor.parse(
                List.of("K=https://h/api/v1/services/rerank/text-rerank/text-rerank?x=1"));
        assertEquals("https://h/api/v1/services/rerank/text-rerank/text-rerank?x=1", m.get("K"));
    }

    @Test
    void keyWithSurroundingSpacesIsTrimmed() {
        Map<String, Object> m = DotenvEnvironmentPostProcessor.parse(List.of("  K  =  v  "));
        assertEquals("v", m.get("K"));
    }

    @Test
    void crlfIsHandled() {
        Map<String, Object> m = DotenvEnvironmentPostProcessor.parse(List.of("K=v\r", "K2=v2\r"));
        assertEquals("v", m.get("K"));
        assertEquals("v2", m.get("K2"));
    }

    @Test
    void innerEscapesAreUnescapedOnlyInDoubleQuotes() {
        assertEquals("a\"b", DotenvEnvironmentPostProcessor.parse(List.of("K=\"a\\\"b\"")).get("K"));
        assertEquals("a\\nb", DotenvEnvironmentPostProcessor.parse(List.of("K='a\\nb'")).get("K"));
    }

    @Test
    void malformedLinesAreIgnoredNotFatal() {
        Map<String, Object> m = DotenvEnvironmentPostProcessor.parse(List.of("JUSTAWORD", "=v", "K=v"));
        assertEquals(1, m.size());
        assertFalse(m.containsKey("JUSTAWORD"));
    }
}