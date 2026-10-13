package tech.qunxu.module.gateway.observability;

import tech.qunxu.module.gateway.admin.service.HealthAdminService;
import tech.qunxu.module.gateway.config.GatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UpstreamHealthIndicator} 单元测试（M8e）：验证「至少一个通道可路由即 UP、有启用通道但全不可路由才 DOWN、
 * 读不到数据一律 UNKNOWN」的保守判定，以及健康自愈关闭与汇总异常时的降级行为。
 */
class UpstreamHealthIndicatorTest {

    private HealthAdminService healthAdminService;
    private GatewayProperties properties;
    private UpstreamHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        healthAdminService = mock(HealthAdminService.class);
        properties = new GatewayProperties();
        properties.getHealth().setStore("redis");
        indicator = new UpstreamHealthIndicator(healthAdminService, properties);
    }

    private static HealthAdminService.HealthSummary summary(int total, int up, int down, int ejected,
                                                            int unknown, int routable, int disabled) {
        return new HealthAdminService.HealthSummary(total, up, down, ejected, unknown, routable, disabled);
    }

    private void stub(HealthAdminService.HealthSummary summary) {
        when(healthAdminService.summary()).thenReturn(Mono.just(summary));
    }

    @Test
    void upWhenAtLeastOneChannelRoutable() {
        // 5 个通道中 2 个被剔除、1 个 DOWN，仍有 2 个可路由 → 整体可用
        stub(summary(5, 2, 1, 2, 0, 2, 0));

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails())
                            .containsEntry("totalChannels", 5)
                            .containsEntry("routableChannels", 2)
                            .containsEntry("ejectedChannels", 2)
                            .containsEntry("downChannels", 1)
                            .containsEntry("healthStore", "redis");
                })
                .verifyComplete();
    }

    @Test
    void downWhenEnabledChannelsExistButNoneRoutable() {
        stub(summary(3, 0, 1, 2, 0, 0, 0));

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                    assertThat(health.getDetails())
                            .containsEntry("routableChannels", 0)
                            .containsKey("reason");
                })
                .verifyComplete();
    }

    @Test
    void unknownWhenNoChannelsConfigured() {
        stub(summary(0, 0, 0, 0, 0, 0, 0));

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
                    assertThat(health.getDetails()).doesNotContainKey("reason");
                })
                .verifyComplete();
    }

    @Test
    void unknownWhenAllChannelsDisabled() {
        // 通道被显式停用是配置意图，不是故障，不应触发 DOWN
        stub(summary(2, 0, 0, 0, 0, 0, 2));

        StepVerifier.create(indicator.health())
                .assertNext(health -> assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN))
                .verifyComplete();
    }

    @Test
    void unknownWhenHealthSelfHealingDisabled() {
        properties.getHealth().setEnabled(false);

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
                    assertThat(health.getDetails()).containsKey("reason");
                })
                .verifyComplete();

        verify(healthAdminService, never()).summary();
    }

    @Test
    void unknownWhenSummaryFails() {
        when(healthAdminService.summary())
                .thenReturn(Mono.error(new IllegalStateException("table not found")));

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
                    assertThat((String) health.getDetails().get("reason")).contains("IllegalStateException");
                })
                .verifyComplete();
    }

    @Test
    void reportsDetailsForPartiallyUnknownChannels() {
        stub(summary(4, 1, 0, 0, 3, 1, 0));

        StepVerifier.create(indicator.health())
                .assertNext(health -> assertThat(health.getDetails())
                        .containsEntry("unknownChannels", 3)
                        .containsEntry("upChannels", 1)
                        .containsEntry("disabledChannels", 0))
                .verifyComplete();
    }

    @Test
    void reflectsConfiguredHealthStoreInDetails() {
        properties.getHealth().setStore("in-memory");
        stub(summary(1, 1, 0, 0, 0, 1, 0));

        Health health = indicator.health().block(Duration.ofSeconds(5));

        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("healthStore", "in-memory")
                .containsEntry("totalChannels", 1);
        // 仍有可路由通道时不应给出故障原因，避免误导告警
        assertThat(health.getDetails()).doesNotContainKey("reason");
    }
}
