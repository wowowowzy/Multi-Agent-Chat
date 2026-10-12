package com.example.agentscopegroupchat.model;

import com.example.agentscopegroupchat.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 轻量 OpenAI 兼容 embedding 客户端（/v1/embeddings），不依赖 starter。
 *
 * <p>未配置时 {@link #isEnabled()} 返回 false，记忆写入仅存文本、召回退化为最近若干条。
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);
    private static final String DEFAULT_BASE = "https://api.openai.com/v1";

    /** 向量 + 本次调用消耗的 token。 */
    public record EmbeddingResult(float[] vector, int tokens) {
    }

    private final AppProperties.Endpoint ep;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public EmbeddingClient(AppProperties props, ObjectMapper mapper) {
        this.ep = props.getModel().getEmbedding();
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean isEnabled() {
        return ep.isConfigured();
    }

    public int dimensionHint() {
        return 0;
    }

    /** 单条文本向量化，失败返回 null。 */
    public EmbeddingResult embed(String text) {
        if (!isEnabled() || !StringUtils.hasText(text)) {
            return null;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", ep.getModel());
            body.put("input", text);
            String json = mapper.writeValueAsString(body);

            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(endpointUrl()))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (StringUtils.hasText(ep.getApiKey())) {
                rb.header("Authorization", "Bearer " + ep.getApiKey());
            }
            HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("embedding 调用失败 HTTP {}：{}", resp.statusCode(), truncate(resp.body()));
                return null;
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode dataArr = root.path("data");
            if (!dataArr.isArray() || dataArr.isEmpty()) {
                return null;
            }
            JsonNode vecNode = dataArr.get(0).path("embedding");
            float[] vec = new float[vecNode.size()];
            for (int i = 0; i < vecNode.size(); i++) {
                vec[i] = (float) vecNode.get(i).asDouble();
            }
            int tokens = root.path("usage").path("total_tokens").asInt(0);
            return new EmbeddingResult(vec, tokens);
        } catch (Exception e) {
            log.warn("embedding 调用异常：{}", e.getMessage());
            return null;
        }
    }

    private String endpointUrl() {
        String base = StringUtils.hasText(ep.getBaseUrl()) ? ep.getBaseUrl().trim() : DEFAULT_BASE;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.endsWith("/embeddings") ? base : base + "/embeddings";
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}