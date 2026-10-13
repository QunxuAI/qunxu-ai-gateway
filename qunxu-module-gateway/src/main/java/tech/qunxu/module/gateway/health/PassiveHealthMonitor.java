package tech.qunxu.module.gateway.health;

import tech.qunxu.module.gateway.common.enums.HealthEventType;
import tech.qunxu.module.gateway.common.enums.HealthStatus;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 被动健康监控：依据真实调用结果维护通道健康，实现「连续失败/凭证过期→剔除冷却」「连续成功→自动恢复」。
 *
 * <p>剔除判据：下游 401/403（{@link ErrorCode#AUTH_EXPIRED}）<b>立即剔除</b>并记 AUTH_EXPIRED 事件（不激进重试，
 * 但允许故障转移到其他供应商通道）；其余失败累计 {@code consecutiveFailures}，达
 * {@code gateway.health.failure-threshold} 则剔除并记 EJECT 事件。恢复判据：原为 DOWN/EJECTED 的通道在半开期
 * 真实调用连续成功达 {@code recovery-success-threshold} 次则置 UP 并记 RECOVER 事件。</p>
 *
 * <p>所有写入均为「尽力而为」：{@code *Async} 方法以 fire-and-forget 订阅，失败仅告警，绝不影响主调用链。
 * 计划提及的 Resilience4j 按通道熔断可由本监控的 Redis 计数 + 剔除等价承载（共享、可观测、跨实例），
 * 如需进程内快速短路可在后续以 R4J 装饰适配器调用叠加。</p>
 */
@Component
public class PassiveHealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(PassiveHealthMonitor.class);

    private final HealthStore healthStore;
    private final HealthEventLog eventLog;
    private final GatewayProperties properties;

    public PassiveHealthMonitor(HealthStore healthStore, HealthEventLog eventLog, GatewayProperties properties) {
        this.healthStore = healthStore;
        this.eventLog = eventLog;
        this.properties = properties;
    }

    /** 记录一次成功调用（响应式）。原为不健康且连续成功达阈值时自动恢复。 */
    public Mono<Void> recordSuccess(Long channelId, Long providerId, long latencyMs) {
        if (channelId == null || !properties.getHealth().isEnabled()) {
            return Mono.empty();
        }
        double alpha = properties.getHealth().getLatencyAlpha();
        int recoveryThreshold = Math.max(1, properties.getHealth().getRecoverySuccessThreshold());
        return healthStore.get(channelId)
                .flatMap(cur -> {
                    ChannelHealth next = cur.afterSuccess(latencyMs, alpha);
                    boolean wasUnhealthy = cur.status() == HealthStatus.DOWN || cur.status() == HealthStatus.EJECTED;
                    if (wasUnhealthy && next.consecutiveSuccesses() >= recoveryThreshold) {
                        ChannelHealth recovered = next.recovered();
                        return healthStore.save(recovered)
                                .then(eventLog.record(channelId, providerId, HealthEventType.RECOVER,
                                        cur.status(), HealthStatus.UP,
                                        "被动恢复：半开期连续成功 " + next.consecutiveSuccesses() + " 次"));
                    }
                    return healthStore.save(next);
                })
                .onErrorResume(e -> {
                    log.warn("记录通道 {} 成功健康失败: {}", channelId, e.toString());
                    return Mono.empty();
                });
    }

    /** 记录一次失败调用（响应式）。凭证过期立即剔除；其余累计失败达阈值剔除。 */
    public Mono<Void> recordFailure(Long channelId, Long providerId, Throwable error) {
        return applyFailure(channelId, providerId, errorCodeOf(error), describe(error), false);
    }

    /**
     * 记录一次主动探测失败。与真实调用失败等价计数与剔除，但事件类型标记为 {@code PROBE_FAIL}
     * （凭证过期仍优先标记 {@code AUTH_EXPIRED}）；未达剔除阈值时也记录 PROBE_FAIL 以便可观测。
     */
    public Mono<Void> recordProbeFailure(Long channelId, Long providerId, HealthResult result) {
        ErrorCode code = result != null ? result.errorCode() : null;
        String detail = (result != null && result.message() != null) ? result.message() : "主动探测失败";
        return applyFailure(channelId, providerId, code, detail, true);
    }

    private Mono<Void> applyFailure(Long channelId, Long providerId, ErrorCode code, String detail, boolean fromProbe) {
        if (channelId == null || !properties.getHealth().isEnabled()) {
            return Mono.empty();
        }
        boolean authExpired = code == ErrorCode.AUTH_EXPIRED;
        int failureThreshold = Math.max(1, properties.getHealth().getFailureThreshold());
        long cooldown = properties.getHealth().getEjectCooldownMs();
        return healthStore.get(channelId)
                .flatMap(cur -> {
                    ChannelHealth next = cur.afterFailure();
                    boolean shouldEject = authExpired || next.consecutiveFailures() >= failureThreshold;
                    if (!shouldEject) {
                        Mono<Void> save = healthStore.save(next);
                        if (fromProbe) {
                            return save.then(eventLog.record(channelId, providerId, HealthEventType.PROBE_FAIL,
                                    cur.status(), next.status(),
                                    "主动探测失败 " + next.consecutiveFailures() + "/" + failureThreshold + " | " + detail));
                        }
                        return save;
                    }
                    ChannelHealth ejected = next.ejected(cooldown);
                    HealthEventType type;
                    String head;
                    if (authExpired) {
                        type = HealthEventType.AUTH_EXPIRED;
                        head = "下游凭证无效或过期(401/403)";
                    } else if (fromProbe) {
                        type = HealthEventType.PROBE_FAIL;
                        head = "主动探测连续失败 " + next.consecutiveFailures() + " 次达阈值 " + failureThreshold;
                    } else {
                        type = HealthEventType.EJECT;
                        head = "连续失败 " + next.consecutiveFailures() + " 次达阈值 " + failureThreshold;
                    }
                    String reason = head + " | " + detail;
                    return healthStore.save(ejected)
                            .then(eventLog.record(channelId, providerId, type,
                                    cur.status(), HealthStatus.EJECTED, reason));
                })
                .onErrorResume(e -> {
                    log.warn("记录通道 {} 失败健康失败: {}", channelId, e.toString());
                    return Mono.empty();
                });
    }

    /** fire-and-forget 记录成功。 */
    public void recordSuccessAsync(Long channelId, Long providerId, long latencyMs) {
        subscribeQuietly(recordSuccess(channelId, providerId, latencyMs));
    }

    /** fire-and-forget 记录失败。 */
    public void recordFailureAsync(Long channelId, Long providerId, Throwable error) {
        subscribeQuietly(recordFailure(channelId, providerId, error));
    }

    private void subscribeQuietly(Mono<Void> mono) {
        mono.subscribe(null, e -> log.debug("健康回写订阅异常: {}", e.toString()));
    }

    /**
     * 是否适合故障转移到其他通道。下游类错误（凭证过期/供应商错误/超时/限流）可转移；
     * 客户端与配置类错误（参数/未实现/模型不存在/围栏拦截/无通道等）不转移。
     */
    public boolean isFailoverEligible(Throwable error) {
        ErrorCode code = errorCodeOf(error);
        if (code == null) {
            return true;   // 未知异常（多为下游/网络）允许转移
        }
        return switch (code) {
            case AUTH_EXPIRED, PROVIDER_ERROR, PROVIDER_TIMEOUT, RATE_LIMITED -> true;
            default -> false;
        };
    }

    /** 从异常链中提取 {@link ErrorCode}（若有）。 */
    public static ErrorCode errorCodeOf(Throwable error) {
        Throwable cur = error;
        int depth = 0;
        while (cur != null && depth++ < 8) {
            if (cur instanceof GatewayException ge) {
                return ge.getErrorCode();
            }
            cur = cur.getCause();
        }
        return null;
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        if (error instanceof GatewayException ge && ge.getProviderDetail() != null) {
            return ge.getErrorCode() + ": " + ge.getProviderDetail();
        }
        String msg = error.getMessage();
        return error.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }
}
