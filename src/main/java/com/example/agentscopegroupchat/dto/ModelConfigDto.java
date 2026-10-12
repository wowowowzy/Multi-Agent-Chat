package com.example.agentscopegroupchat.dto;

import java.util.List;

/**
 * 模型配置（首启向导 / 管理台共用）。
 *
 * <p>apiKey 只回传掩码：管理台不需要看到明文，明文只存在于 {@code .env} 与服务进程内。
 * 前端拿到掩码后原样显示即可，用户不重填就不会改动磁盘上的真实 key。
 */
public record ModelConfigDto(
        EndpointDetail chat,
        EndpointDetail gate,
        EndpointDetail embedding,
        EndpointDetail rerank,
        /** 向量维度（必须与 embedding 模型输出维度一致）。 */
        int vecDimension,
        /** 实际生效的 .env 路径（首启向导与运行期写盘用同一个文件）。 */
        String envPath,
        boolean envFileExists,
        /** 被系统环境变量强制覆盖的键：这些项写 .env 也不会在重启后生效。 */
        List<String> envOverrideKeys,
        /** 保存后的提醒（例如改了向量维度需要重启并重建记忆）。 */
        List<String> warnings) {

    /** 单个用途的当前配置（apiKey 已脱敏）。 */
    public record EndpointDetail(
            String baseUrl,
            String model,
            String apiKeyMasked,
            boolean apiKeySet,
            boolean disableThinking,
            /** model 非空即视为已配置。 */
            boolean configured) {
    }
}