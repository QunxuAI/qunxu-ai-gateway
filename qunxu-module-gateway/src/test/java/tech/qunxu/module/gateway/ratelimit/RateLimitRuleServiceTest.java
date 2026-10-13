package tech.qunxu.module.gateway.ratelimit;

import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.persistence.entity.RateLimitRuleEntity;
import tech.qunxu.module.gateway.persistence.mapper.RateLimitRuleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RateLimitRuleService} 单元测试：快照未载入时回退 yaml；载入后按
 * 「覆盖 &gt; 默认」解析，默认行被停用时回退 yaml 默认；配额按作用维度选覆盖。
 */
class RateLimitRuleServiceTest {

    private GatewayProperties properties;
    private RateLimitRuleMapper mapper;

    @BeforeEach
    void setUp() {
        properties = new GatewayProperties();
        properties.getRateLimit().setDefaultPermitsPerMinute(600);
        properties.getRateLimit().setDefaultModelPermitsPerMinute(300);
        properties.getQuota().setDefaultDailyTokens(0L);
        mapper = mock(RateLimitRuleMapper.class);
    }

    private RateLimitRuleService service() {
        return new RateLimitRuleService(mapper, properties);
    }

    private static RateLimitRuleEntity row(String type, String target, Integer permits, Long daily, Integer enabled) {
        RateLimitRuleEntity e = new RateLimitRuleEntity();
        e.setId(1L);
        e.setName(type + (target == null ? "" : ":" + target));
        e.setRuleType(type);
        e.setTarget(target);
        e.setPermitsPerMinute(permits);
        e.setDailyTokens(daily);
        e.setEnabled(enabled);
        return e;
    }

    // ------------------------------------------------------------ 未载入 → yaml 回退

    @Test
    void unloadedSnapshotFallsBackToYaml() {
        RateLimitRuleService service = service();
        assertThat(service.isLoaded()).isFalse();

        assertThat(service.callerPermits("alice", "default")).isEqualTo(600);
        assertThat(service.modelPermits("deepseek-chat")).isEqualTo(300);
        assertThat(service.dailyTokens("alice", "default", false)).isEqualTo(0L);

        // yaml 覆盖在回退态仍生效
        properties.getRateLimit().setCallerOverrides(java.util.Map.of("vip", 2000));
        assertThat(service.callerPermits("vip", "default")).isEqualTo(2000);
    }

    // ------------------------------------------------------------ 载入后 → 数据库为准

    @Test
    void loadedSnapshotResolvesPriority() {
        when(mapper.selectList(any())).thenReturn(List.of(
                row(RateLimitRuleService.CALLER_DEFAULT, null, 600, null, 1),
                row(RateLimitRuleService.CALLER_OVERRIDE, "vip", 2000, null, 1),
                row(RateLimitRuleService.TENANT_OVERRIDE, "team-a", 1000, null, 1),
                row(RateLimitRuleService.MODEL_DEFAULT, null, 300, null, 1),
                row(RateLimitRuleService.MODEL_OVERRIDE, "gpt-4o", 50, null, 1),
                row(RateLimitRuleService.QUOTA_DEFAULT, null, 0, 1000L, 1),
                row(RateLimitRuleService.QUOTA_CALLER, "alice", 0, 5000L, 1),
                row(RateLimitRuleService.QUOTA_TENANT, "team-a", 0, 2000L, 1)));

        RateLimitRuleService service = service();
        service.refresh().block();
        assertThat(service.isLoaded()).isTrue();

        // 调用人覆盖 > 租户覆盖 > 默认
        assertThat(service.callerPermits("vip", "team-a")).isEqualTo(2000);
        assertThat(service.callerPermits("bob", "team-a")).isEqualTo(1000);
        assertThat(service.callerPermits("bob", "default")).isEqualTo(600);
        // 模型覆盖 > 模型默认
        assertThat(service.modelPermits("gpt-4o")).isEqualTo(50);
        assertThat(service.modelPermits("deepseek-chat")).isEqualTo(300);
        // 配额：caller 维度取调用人覆盖 > 租户覆盖 > 默认；tenant 维度只取租户覆盖 > 默认
        assertThat(service.dailyTokens("alice", "team-a", false)).isEqualTo(5000L);
        assertThat(service.dailyTokens("bob", "team-a", false)).isEqualTo(2000L);
        assertThat(service.dailyTokens("bob", "default", false)).isEqualTo(1000L);
        assertThat(service.dailyTokens("alice", "team-a", true)).isEqualTo(2000L);
        assertThat(service.dailyTokens("bob", "default", true)).isEqualTo(1000L);
    }

    // ------------------------------------------------------------ 默认行停用 → 回退 yaml 默认

    @Test
    void disabledDefaultRuleFallsBackToYamlDefault() {
        when(mapper.selectList(any())).thenReturn(List.of(
                row(RateLimitRuleService.CALLER_DEFAULT, null, 600, null, 0),   // 停用
                row(RateLimitRuleService.CALLER_OVERRIDE, "vip", 2000, null, 1),
                row(RateLimitRuleService.QUOTA_DEFAULT, null, 0, 1000L, 0)));   // 停用

        RateLimitRuleService service = service();
        service.refresh().block();

        assertThat(service.callerPermits("vip", "default")).isEqualTo(2000);   // 覆盖不受影响
        assertThat(service.callerPermits("bob", "default")).isEqualTo(600);    // 默认停用 → yaml
        assertThat(service.dailyTokens("bob", "default", false)).isEqualTo(0L); // 配额默认停用 → yaml
    }

    // ------------------------------------------------------------ 停用的覆盖行不生效

    @Test
    void disabledOverrideIsIgnored() {
        when(mapper.selectList(any())).thenReturn(List.of(
                row(RateLimitRuleService.CALLER_DEFAULT, null, 600, null, 1),
                row(RateLimitRuleService.CALLER_OVERRIDE, "vip", 2000, null, 0), // 停用
                row(RateLimitRuleService.MODEL_OVERRIDE, "gpt-4o", 50, null, 0))); // 停用

        RateLimitRuleService service = service();
        service.refresh().block();

        assertThat(service.callerPermits("vip", "default")).isEqualTo(600);
        assertThat(service.modelPermits("gpt-4o")).isEqualTo(300);
    }

    // ------------------------------------------------------------ 刷新失败 → 沿用现有快照

    @Test
    void refreshFailureKeepsExistingSnapshot() {
        when(mapper.selectList(any()))
                .thenReturn(List.of(row(RateLimitRuleService.CALLER_DEFAULT, null, 600, null, 1)))
                .thenThrow(new RuntimeException("db down"));

        RateLimitRuleService service = service();
        service.refresh().block();
        assertThat(service.callerPermits("bob", "default")).isEqualTo(600);

        // 第二次刷新失败：快照保持已载入状态与旧值
        Integer result = service.refresh().block();
        assertThat(result).isEqualTo(1);
        assertThat(service.isLoaded()).isTrue();
        assertThat(service.callerPermits("bob", "default")).isEqualTo(600);
    }

    // ------------------------------------------------------------ 全量列表（管理端视图）

    @Test
    void allRulesReturnsRowsIncludingDisabled() {
        when(mapper.selectList(any())).thenReturn(List.of(
                row(RateLimitRuleService.CALLER_DEFAULT, null, 600, null, 1),
                row(RateLimitRuleService.MODEL_OVERRIDE, "gpt-4o", 50, null, 0)));

        RateLimitRuleService service = service();
        List<RateLimitRuleEntity> rules = service.allRules().block();
        assertThat(rules).hasSize(2);
        assertThat(rules.get(1).getRuleType()).isEqualTo(RateLimitRuleService.MODEL_OVERRIDE);
    }
}
