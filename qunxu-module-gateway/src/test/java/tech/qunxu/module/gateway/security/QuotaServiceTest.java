package tech.qunxu.module.gateway.security;

import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.persistence.mapper.RateLimitRuleMapper;
import tech.qunxu.module.gateway.ratelimit.RateLimitRuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link QuotaService} 单元测试（M8e）：覆盖限额解析优先级（调用人 &gt; 租户 &gt; 默认，及租户维度汇总）、
 * 按日滚动的计数键、Redis 读写路径、以及 Redis 缺失/异常时降级为进程内计数并失败开放的行为。
 *
 * <p>规则服务使用「未载入快照」实例（{@code isLoaded=false}），限额解析回退 yaml 属性，与升级前行为一致。</p>
 */
class QuotaServiceTest {

    private GatewayProperties properties;

    @BeforeEach
    void setUp() {
        properties = new GatewayProperties();
    }

    /** 构造一个「无 Redis」的配额服务，走进程内计数路径。 */
    private QuotaService localService() {
        return new QuotaService(emptyRedisProvider(), properties, newRuleService());
    }

    /** 构造一个从未载入数据库快照的规则服务（回退 yaml）。 */
    private RateLimitRuleService newRuleService() {
        return new RateLimitRuleService(mock(RateLimitRuleMapper.class), properties);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ReactiveStringRedisTemplate> emptyRedisProvider() {
        ObjectProvider<ReactiveStringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ReactiveStringRedisTemplate> providerOf(ReactiveStringRedisTemplate template) {
        ObjectProvider<ReactiveStringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(template);
        return provider;
    }

    private static String today() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    // ---- 开关与限额解析 ----

    @Test
    void disabledByDefault() {
        assertThat(properties.getQuota().isEnabled()).isFalse();
        assertThat(localService().isEnabled()).isFalse();
    }

    @Test
    void resolvesCallerLimitWithOverridePrecedence() {
        properties.getQuota().setDefaultDailyTokens(1000L);
        properties.getQuota().setCallerOverrides(Map.of("alice", 5000L));
        QuotaService service = localService();

        assertThat(service.resolveLimit("alice", "default")).isEqualTo(5000L);
        // 无调用人覆盖时回落到全局默认
        assertThat(service.resolveLimit("bob", "unknown-tenant")).isEqualTo(1000L);
    }

    @Test
    void fallsBackToTenantOverrideBeforeDefault() {
        properties.getQuota().setDefaultDailyTokens(1000L);
        properties.getQuota().setTenantOverrides(Map.of("team-a", 2000L));
        QuotaService service = localService();

        assertThat(service.resolveLimit("bob", "team-a")).isEqualTo(2000L);
        assertThat(service.resolveLimit("bob", "team-b")).isEqualTo(1000L);
    }

    @Test
    void tenantScopeIgnoresCallerOverride() {
        properties.getQuota().setScope("tenant");
        properties.getQuota().setDefaultDailyTokens(1000L);
        properties.getQuota().setCallerOverrides(Map.of("alice", 5000L));
        properties.getQuota().setTenantOverrides(Map.of("team-a", 2000L));
        QuotaService service = localService();

        // 租户维度汇总：按租户覆盖生效，调用人覆盖被忽略
        assertThat(service.resolveLimit("alice", "team-a")).isEqualTo(2000L);
        assertThat(service.resolveLimit("alice", "team-b")).isEqualTo(1000L);
    }

    @Test
    void explicitZeroOverrideMeansUnlimited() {
        properties.getQuota().setDefaultDailyTokens(1000L);
        properties.getQuota().setCallerOverrides(Map.of("vip", 0L));

        assertThat(localService().resolveLimit("vip", "default")).isZero();
    }

    @Test
    void quotaKeyContainsScopeIdAndDay() {
        QuotaService service = localService();

        assertThat(service.quotaKey("alice", "team-a"))
                .isEqualTo(QuotaService.KEY_PREFIX + "caller:alice:" + today());

        properties.getQuota().setScope("tenant");
        assertThat(service.quotaKey("alice", "team-a"))
                .isEqualTo(QuotaService.KEY_PREFIX + "tenant:team-a:" + today());
    }

    @Test
    void blankIdentityFallsBackToAnonymous() {
        QuotaService service = localService();

        assertThat(service.quotaKey(null, null))
                .isEqualTo(QuotaService.KEY_PREFIX + "caller:anonymous:" + today());
    }

    // ---- 判定与计数（进程内降级路径）----

    @Test
    void nonPositiveLimitMeansUnlimited() {
        QuotaService service = localService();
        String key = service.quotaKey("alice", "default");

        StepVerifier.create(service.check(key, 0L))
                .assertNext(decision -> {
                    assertThat(decision.allowed()).isTrue();
                    assertThat(decision.unlimited()).isTrue();
                    assertThat(decision.used()).isEqualTo(-1L);
                    assertThat(decision.remaining()).isEqualTo(-1L);
                })
                .verifyComplete();
    }

    @Test
    void consumesAndThenExceedsLimit() {
        properties.getQuota().setEnabled(true);
        properties.getQuota().setDefaultDailyTokens(100L);
        QuotaService service = localService();
        String key = service.quotaKey("alice", "default");

        StepVerifier.create(service.check(key, 100L))
                .assertNext(decision -> {
                    assertThat(decision.allowed()).isTrue();
                    assertThat(decision.used()).isZero();
                    assertThat(decision.remaining()).isEqualTo(100L);
                })
                .verifyComplete();

        StepVerifier.create(service.consume(key, 60L))
                .expectNext(60L)
                .verifyComplete();

        StepVerifier.create(service.consume(key, 50L))
                .expectNext(110L)
                .verifyComplete();

        StepVerifier.create(service.check(key, 100L))
                .assertNext(decision -> {
                    assertThat(decision.allowed()).isFalse();
                    assertThat(decision.used()).isEqualTo(110L);
                    assertThat(decision.remaining()).isZero();
                })
                .verifyComplete();
    }

    @Test
    void consumeWithNonPositiveTokensReadsCurrentUsage() {
        QuotaService service = localService();
        String key = service.quotaKey("alice", "default");

        StepVerifier.create(service.consume(key, 30L).then(service.consume(key, 0L)))
                .expectNext(30L)
                .verifyComplete();
        StepVerifier.create(service.usage(key))
                .expectNext(30L)
                .verifyComplete();
    }

    @Test
    void consumeIgnoresBlankKey() {
        StepVerifier.create(localService().consume("  ", 100L))
                .expectNext(0L)
                .verifyComplete();
    }

    // ---- Redis 路径 ----

    @Test
    @SuppressWarnings("unchecked")
    void readsUsageFromRedis() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> valueOps = mock(ReactiveValueOperations.class);
        when(redis.<String, String>opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(Mono.just("900"));

        QuotaService service = new QuotaService(providerOf(redis), properties, newRuleService());
        String key = service.quotaKey("alice", "default");

        StepVerifier.create(service.check(key, 1000L))
                .assertNext(decision -> {
                    assertThat(decision.allowed()).isTrue();
                    assertThat(decision.used()).isEqualTo(900L);
                    assertThat(decision.remaining()).isEqualTo(100L);
                })
                .verifyComplete();
        verify(valueOps).get(key);
    }

    @Test
    @SuppressWarnings("unchecked")
    void incrementsUsageInRedisAndRefreshesTtl() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> valueOps = mock(ReactiveValueOperations.class);
        when(redis.<String, String>opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString(), anyLong())).thenReturn(Mono.just(150L));
        when(redis.expire(anyString(), eq(Duration.ofHours(36)))).thenReturn(Mono.just(true));

        QuotaService service = new QuotaService(providerOf(redis), properties, newRuleService());
        String key = service.quotaKey("alice", "default");

        StepVerifier.create(service.consume(key, 150L))
                .expectNext(150L)
                .verifyComplete();
        verify(valueOps).increment(key, 150L);
        verify(redis).expire(eq(key), eq(Duration.ofHours(36)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void unparsableRedisValueIsTreatedAsZero() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> valueOps = mock(ReactiveValueOperations.class);
        when(redis.<String, String>opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(Mono.just("not-a-number"));

        QuotaService service = new QuotaService(providerOf(redis), properties, newRuleService());

        StepVerifier.create(service.usage(service.quotaKey("alice", "default")))
                .expectNext(0L)
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisFailureDegradesToUnknownAndFailsOpen() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> valueOps = mock(ReactiveValueOperations.class);
        when(redis.<String, String>opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(Mono.error(new IllegalStateException("redis down")));

        QuotaService service = new QuotaService(providerOf(redis), properties, newRuleService());
        String key = service.quotaKey("alice", "default");

        // 已用量未知 → 失败开放，绝不因配额组件故障阻断业务
        StepVerifier.create(service.check(key, 1000L))
                .assertNext(decision -> {
                    assertThat(decision.allowed()).isTrue();
                    assertThat(decision.used()).isEqualTo(-1L);
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisFailureOnWriteFallsBackToLocalCounter() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> valueOps = mock(ReactiveValueOperations.class);
        when(redis.<String, String>opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString(), anyLong())).thenReturn(Mono.error(new IllegalStateException("redis down")));

        QuotaService service = new QuotaService(providerOf(redis), properties, newRuleService());
        String key = service.quotaKey("alice", "default");

        StepVerifier.create(service.consume(key, 80L))
                .expectNext(80L)
                .verifyComplete();
        // 降级后的进程内计数可被后续读取复用
        verify(redis, never()).expire(anyString(), eq(Duration.ofHours(36)));
    }
}
