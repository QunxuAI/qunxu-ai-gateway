package tech.qunxu.module.gateway.observability;

import tech.qunxu.module.gateway.admin.service.HealthAdminService;
import tech.qunxu.module.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.AbstractReactiveHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 上游通道健康指示器（Actuator {@code /actuator/health} 中的 {@code upstream} 项）。
 *
 * <p>DB 与 Redis 的健康由 Spring Boot 自带的 {@code db} / {@code redis} 指示器覆盖，本指示器只回答
 * 「<b>网关还能不能把请求转发出去</b>」这个业务问题：汇总全部模型通道的运行时健康，复用管理接口的
 * {@link HealthAdminService#summary()}，保证 Actuator 与 {@code /admin/health/summary} 口径一致。</p>
 *
 * <p>状态判定刻意保守，避免误报告警：</p>
 * <ul>
 *   <li>{@code UP}：至少有一个通道可路由（部分通道被剔除/宕机不影响整体可用性，细节见 details）；</li>
 *   <li>{@code DOWN}：存在启用通道但<b>无一</b>可路由，此时网关已丧失转发能力；</li>
 *   <li>{@code UNKNOWN}：未配置任何通道、健康自愈被显式关闭，或汇总本身失败（如库表尚未就绪）。
 *       「读不到」与「全挂了」是两件事，后者才该触发 DOWN。</li>
 * </ul>
 */
@Component
public class UpstreamHealthIndicator extends AbstractReactiveHealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(UpstreamHealthIndicator.class);

    private final HealthAdminService healthAdminService;
    private final GatewayProperties properties;

    public UpstreamHealthIndicator(HealthAdminService healthAdminService, GatewayProperties properties) {
        super("上游模型通道");
        this.healthAdminService = healthAdminService;
        this.properties = properties;
    }

    @Override
    protected Mono<Health> doHealthCheck(Health.Builder builder) {
        if (!properties.getHealth().isEnabled()) {
            builder.status(Status.UNKNOWN)
                    .withDetail("reason", "健康自愈已关闭（gateway.health.enabled=false）")
                    .withDetail("healthStore", properties.getHealth().getStore());
            return Mono.just(builder.build());
        }
        return healthAdminService.summary()
                .map(summary -> describe(builder, summary))
                .onErrorResume(e -> {
                    // 汇总依赖库表与 Redis，任一不可用都退化为 UNKNOWN，不让基础设施抖动伪装成上游全挂
                    log.debug("汇总上游通道健康失败，按 UNKNOWN 上报: {}", e.toString());
                    builder.status(Status.UNKNOWN)
                            .withDetail("reason", "健康数据暂不可用: " + e.getClass().getSimpleName())
                            .withDetail("healthStore", properties.getHealth().getStore());
                    return Mono.just(builder.build());
                });
    }

    private Health describe(Health.Builder builder, HealthAdminService.HealthSummary summary) {
        int enabled = summary.total() - summary.disabled();
        boolean routable = summary.routable() > 0;
        builder.status(routable ? Status.UP : (enabled == 0 ? Status.UNKNOWN : Status.DOWN))
                .withDetail("healthStore", properties.getHealth().getStore())
                .withDetail("totalChannels", summary.total())
                .withDetail("routableChannels", summary.routable())
                .withDetail("upChannels", summary.up())
                .withDetail("downChannels", summary.down())
                .withDetail("ejectedChannels", summary.ejected())
                .withDetail("unknownChannels", summary.unknown())
                .withDetail("disabledChannels", summary.disabled());
        if (!routable && enabled > 0) {
            builder.withDetail("reason", "全部启用通道均不可路由，网关已丧失上游转发能力");
        }
        return builder.build();
    }
}
