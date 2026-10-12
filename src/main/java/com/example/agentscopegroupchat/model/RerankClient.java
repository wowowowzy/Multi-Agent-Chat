package com.example.agentscopegroupchat.model;

import com.example.agentscopegroupchat.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 轻量 rerank 客户端（rerank 无 OpenAI 兼容标准，故自定义抽象）。
 *
 * <p>未配置时 {@link #isEnabled()} 返回 false，召回直接使用向量分数排序（自动跳过 rerank）。
 *
 * <p>请求形态：优先按阿里云 DashScope/百炼 原生 rerank 形态发送
 * （{@code {"model","input":{"query","documents"},"parameters":{...}}}，响应在 {@code output.results}）；
 * 若网关返回非 2xx，再回退一次 Cohere/Jina 扁平形态
 * （{@code {"model","query","documents","top_n"}}，响应在 {@code results}/{@code data}）。
 * 两种响应统一按 {@code index + relevance_score|score} 解析。
 */
@Component
public class RerankClient {

    private static final Logger log = LoggerFactory.getLogger(RerankClient.class);

    /** 重排结果：原文档下标 + 相关性分数。 */
    public record Reranked(int index, double score) {
    }

    private final AppProperties.Endpoint ep;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public RerankClient(AppProperties props, ObjectMapper mapper) {
        this.ep = props.getModel().getRerank();
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean isEnabled() {
        return ep.isConfigured();
    }

    /** 对候选文档重排，返回按下标引用的有序结果；失败返回 null。 */
    public List<Reranked> rerank(String query, List<String> documents, int topN) {
        if (!isEnabled() || documents == null || documents.isEmpty()) {
            return null;
        }
        int n = Math.min(topN, documents.size());

        // 形态一：DashScope/百炼 原生 rerank（本网关即此形态）
        Map<String, Object> nativeBody = new LinkedHashMap<>();
        nativeBody.put("model", ep.getModel());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", query);
        input.put("documents", documents);
        nativeBody.put("input", input);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("return_documents", false);
        parameters.put("top_n", n);
        nativeBody.put("parameters", parameters);
        List<Reranked> out = call(nativeBody);
        if (out != null) {
            return out;
        }

        // 形态二：Cohere/Jina 等扁平形态兜底
        Map<String, Object> flatBody = new LinkedHashMap<>();
        flatBody.put("model", ep.getModel());
        flatBody.put("query", query);
        flatBody.put("documents", documents);
        flatBody.put("top_n", n);
        return call(flatBody);
    }

    private List<Reranked> call(Map<String, Object> body) {
        try {
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
                log.warn("rerank 调用失败 HTTP {}：{}", resp.statusCode(), truncate(resp.body()));
                return null;
            }
            JsonNode root = mapper.readTree(resp.body());
            // 依次尝试 output.results（DashScope 原生）、results、data
            JsonNode results = root.path("output").path("results");
            if (!results.isArray()) {
                results = root.path("results");
            }
            if (!results.isArray()) {
                results = root.path("data");
            }
            if (!results.isArray()) {
                return null;
            }
            List<Reranked> out = new ArrayList<>();
            for (JsonNode node : results) {
                int idx = node.path("index").asInt(-1);
                double score = node.has("relevance_score") ? node.get("relevance_score").asDouble()
                        : node.path("score").asDouble();
                if (idx >= 0) {
                    out.add(new Reranked(idx, score));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("rerank 调用异常：{}", e.getMessage());
            return null;
        }
    }

    private String endpointUrl() {
        String base = StringUtils.hasText(ep.getBaseUrl()) ? ep.getBaseUrl().trim() : "";
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        // base-url 已指向具体 rerank 端点时原样使用（不要求"/rerank"结尾，
        // 因为 DashScope 端点形如 .../text-rerank/text-rerank）
        return base.endsWith("rerank") ? base : base + "/rerank";
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}