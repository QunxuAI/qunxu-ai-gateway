package tech.qunxu.module.gateway.admin;

import tech.qunxu.module.gateway.common.enums.HealthStatus;
import tech.qunxu.module.gateway.health.ChannelHealth;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;

/**
 * 通道健康视图：把数据库中的<b>静态配置</b>（通道/逻辑模型/供应商）与 Redis 中的<b>运行时健康</b>
 * （状态、连续成败计数、冷却截止、EWMA 时延）合并为一份管理界面可直接渲染的快照。
 *
 * <p>健康数据缺失（Redis 抖动或从未探测）时按 {@link HealthStatus#UNKNOWN} 呈现且 {@code routable=true}，
 * 与路由层的「失败开放」语义保持一致，避免运维误判为通道故障。</p>
 *
 * <p>{@code routable} 是<b>配置层与运行时健康的合取</b>：通道被显式停用（{@code enabled=0}）时恒为
 * {@code false}，因为路由阶段根本不会取到它。若只看运行时健康，「全部通道被停用」会汇总出
 * {@code routable>0}，使 readiness 探针在网关已无转发能力时仍报 UP。</p>
 *
 * @param channelId            通道 ID
 * @param logicalModelId       逻辑模型 ID
 * @param logicalModel         对外统一模型名
 * @param providerId           供应商 ID
 * @param providerName         供应商名称
 * @param providerCode         供应商编码（ProviderType）
 * @param upstreamModel        下游真实模型名
 * @param protocol             通道协议
 * @param weight               负载均衡权重
 * @param priority             优先级（越小越优先）
 * @param enabled              配置层启用标记（1 启用 0 停用）
 * @param status               运行时健康状态
 * @param routable             当前是否可参与路由（配置层启用 <b>且</b> 运行时健康允许）
 * @param ejected              是否处于剔除冷却期
 * @param consecutiveFailures  连续失败次数
 * @param consecutiveSuccesses 连续成功次数
 * @param ejectedUntil         剔除冷却截止时间（epoch millis），未剔除为 null
 * @param avgLatencyMs         EWMA 平均时延（毫秒），无样本为 null
 * @param healthUpdatedAt      健康快照最近更新时间（epoch millis），从未更新为 null
 */
public record ChannelHealthView(
        Long channelId,
        Long logicalModelId,
        String logicalModel,
        Long providerId,
        String providerName,
        String providerCode,
        String upstreamModel,
        String protocol,
        Integer weight,
        Integer priority,
        Integer enabled,
        HealthStatus status,
        boolean routable,
        boolean ejected,
        int consecutiveFailures,
        int consecutiveSuccesses,
        Long ejectedUntil,
        Double avgLatencyMs,
        Long healthUpdatedAt) {

    /**
     * 由通道配置与健康快照组装视图。
     *
     * @param channel       通道配置（非空）
     * @param logicalModel  逻辑模型名（可空，模型已删除时）
     * @param providerName  供应商名（可空）
     * @param providerCode  供应商编码（可空）
     * @param health        健康快照；{@code null} 时按 {@link ChannelHealth#unknown} 处理
     */
    public static ChannelHealthView of(ModelChannelEntity channel, String logicalModel,
                                       String providerName, String providerCode, ChannelHealth health) {
        Long channelId = channel != null ? channel.getId() : null;
        ChannelHealth h = health != null ? health : ChannelHealth.unknown(channelId);
        Integer enabled = channel != null ? channel.getEnabled() : null;
        return new ChannelHealthView(
                channelId,
                channel != null ? channel.getLogicalModelId() : null,
                logicalModel,
                channel != null ? channel.getProviderId() : null,
                providerName,
                providerCode,
                channel != null ? channel.getUpstreamModel() : null,
                channel != null ? channel.getProtocol() : null,
                channel != null ? channel.getWeight() : null,
                channel != null ? channel.getPriority() : null,
                channel != null ? channel.getEnabled() : null,
                h.status() != null ? h.status() : HealthStatus.UNKNOWN,
                // enabled 为 null 视为启用（历史数据/未显式设置），仅 0 表示停用
                (enabled == null || enabled != 0) && h.isRoutable(),
                h.isEjected(),
                h.consecutiveFailures(),
                h.consecutiveSuccesses(),
                h.ejectedUntil(),
                h.hasLatency() ? h.avgLatencyMs() : null,
                h.updatedAt());
    }
}
