package tech.qunxu.module.gateway.audit;

import tech.qunxu.module.gateway.common.enums.CallStatus;

import java.time.LocalDateTime;

/**
 * 一次调用的审计快照（协议/领域层，不耦合持久化）。由 {@code AuditService} 在请求结束时从
 * {@code RequestContext} 组装，经 {@code BatchAuditWriter} 批量落库为 {@code audit_log}。
 *
 * <p>请求/响应体已按上限截断并按策略脱敏；{@code createdAt} 为调用结束时刻（早于批量写入时刻，更贴近真实时间）。</p>
 *
 * @param traceId          链路追踪 ID
 * @param callerId         调用人标识（JWT sub / 静态令牌映射）
 * @param callerName       调用人可读名称
 * @param tenant           租户
 * @param clientIp         客户端 IP
 * @param protocol         上游协议 OPENAI / ANTHROPIC
 * @param endpoint         端点标识（如 chat.completions）
 * @param logicalModel     对外逻辑模型名
 * @param channelId        实际承载的通道 ID（故障转移后为最终成功通道）
 * @param providerId       供应商 ID
 * @param providerCode     供应商编码
 * @param upstreamModel    下游真实模型名
 * @param requestBody      请求体（截断/脱敏）
 * @param responseBody     响应体或流式聚合文本（截断/脱敏）
 * @param promptTokens     输入 token
 * @param completionTokens 输出 token
 * @param totalTokens      总 token
 * @param latencyMs        总耗时（毫秒）
 * @param firstTokenMs     首字延迟（毫秒），非流式为 null
 * @param status           调用结果 SUCCESS / FAILURE / BLOCKED
 * @param errorCode        归一化错误码（失败时）
 * @param errorMsg         错误信息（截断）
 * @param guardrailHit     命中的安全围栏（M7 填充）
 * @param moderationResult 内容审计结果（M7 填充）
 * @param createdAt        调用结束时刻
 */
public record AuditRecord(
        String traceId,
        String callerId,
        String callerName,
        String tenant,
        String clientIp,
        String protocol,
        String endpoint,
        String logicalModel,
        Long channelId,
        Long providerId,
        String providerCode,
        String upstreamModel,
        String requestBody,
        String responseBody,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        long latencyMs,
        Long firstTokenMs,
        CallStatus status,
        String errorCode,
        String errorMsg,
        String guardrailHit,
        String moderationResult,
        LocalDateTime createdAt) {
}
