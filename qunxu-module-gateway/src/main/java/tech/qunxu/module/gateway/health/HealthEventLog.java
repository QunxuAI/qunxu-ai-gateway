package tech.qunxu.module.gateway.health;

import tech.qunxu.module.gateway.common.enums.HealthEventType;
import tech.qunxu.module.gateway.common.enums.HealthStatus;
import tech.qunxu.module.gateway.observability.GatewayMetrics;
import tech.qunxu.module.gateway.persistence.entity.HealthEventEntity;
import tech.qunxu.module.gateway.persistence.mapper.HealthEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 健康事件日志：把通道健康状态变迁（剔除/恢复/探测失败/凭证过期）追加写入 {@code health_event} 表。
 *
 * <p>MyBatis-Plus 为阻塞 JDBC，写入经 {@link Schedulers#boundedElastic()} 卸载；写失败仅告警不影响主流程
 * （健康自愈不应因审计表不可用而中断）。{@code createdAt} 由 MyBatis-Plus 自动填充。</p>
 *
 * <p>事件同时计入 {@link GatewayMetrics} 的 {@code gateway.channel.health.events} 计数器（剔除/恢复/
 * 探测失败/凭证过期），指标与落库解耦——即使审计表不可写，可观测性仍然完整。</p>
 */
@Service
public class HealthEventLog {

    private static final Logger log = LoggerFactory.getLogger(HealthEventLog.class);
    private static final int MAX_REASON = 500;

    private final HealthEventMapper mapper;
    private final GatewayMetrics metrics;

    public HealthEventLog(HealthEventMapper mapper, GatewayMetrics metrics) {
        this.mapper = mapper;
        this.metrics = metrics;
    }

    /**
     * 记录一条健康事件。
     *
     * @param channelId  通道 ID
     * @param providerId 供应商 ID（可空）
     * @param type       事件类型
     * @param from       变迁前状态（可空）
     * @param to         变迁后状态（可空）
     * @param reason     原因说明（截断至 {@value #MAX_REASON} 字）
     */
    public Mono<Void> record(Long channelId, Long providerId, HealthEventType type,
                             HealthStatus from, HealthStatus to, String reason) {
        if (channelId == null || type == null) {
            return Mono.empty();
        }
        if (metrics != null) {
            metrics.recordHealthEvent(type, reason);
        }
        return Mono.fromRunnable(() -> {
                    HealthEventEntity entity = new HealthEventEntity();
                    entity.setChannelId(channelId);
                    entity.setProviderId(providerId);
                    entity.setEventType(type.name());
                    entity.setFromStatus(from != null ? from.name() : null);
                    entity.setToStatus(to != null ? to.name() : null);
                    entity.setReason(truncate(reason));
                    mapper.insert(entity);
                    if (log.isDebugEnabled()) {
                        log.debug("健康事件 通道={} 类型={} {}→{} 原因={}", channelId, type,
                                from, to, entity.getReason());
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("写入健康事件失败(通道={}, 类型={}): {}", channelId, type, e.toString());
                    return Mono.empty();
                })
                .then();
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_REASON ? s : s.substring(0, MAX_REASON) + "...";
    }
}
