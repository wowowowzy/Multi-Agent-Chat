package com.example.agentscopegroupchat.dto;

/**
 * 模型配置写入请求。
 *
 * <p>语义：字段为 {@code null} 表示"这一项不动"，所以前端可以只提交用户真正改过的项
 * （例如只想换 apiKey，就不必回传 baseUrl/model，避免把掩码当成真值写回去）。
 */
public record ModelConfigRequest(
        EndpointInput chat,
        EndpointInput gate,
        EndpointInput embedding,
        EndpointInput rerank,
        /** 向量维度；null 表示不改。 */
        Integer vecDimension) {

    /**
     * 单个用途的输入。
     *
     * <p>apiKey 三态：{@code null} = 保持原值；{@code clearApiKey=true} = 清空；
     * 非空字符串 = 覆盖。
     */
    public record EndpointInput(
            String baseUrl,
            String apiKey,
            Boolean clearApiKey,
            String model,
            Boolean disableThinking) {
    }
}