package tech.qunxu.module.gateway.router;

import tech.qunxu.module.gateway.config.GatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StickyRouter} 单元测试：Mockito 桩 Redis 值操作，验证绑定键组合、查询命中/无绑定、
 * Redis 异常按 {@code fail-open} 降级（或 {@code fail-closed} 上抛）、写入带 TTL 与解绑。
 */
class StickyRouterTest {

    private ReactiveStringRedisTemplate redis;
    private ReactiveValueOperations<String, String> valueOps;
    private GatewayProperties properties;
    private StickyRouter router;

    @BeforeEach
    void setUp() {
        redis = mock(ReactiveStringRedisTemplate.class);
        valueOps = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        properties = new GatewayProperties();
        properties.getSticky().setTtlSeconds(120);
        router = new StickyRouter(redis, properties);
    }

    private static final String KEY = "gw:sticky:7:alice:conv-1";

    @Test
    void composesSessionKeyWithCallerIsolation() {
        assertThat(StickyRouter.sessionKey("alice", "conv-1")).isEqualTo("alice:conv-1");
        assertThat(StickyRouter.sessionKey("bob", "conv-1")).isEqualTo("bob:conv-1");
    }

    @Test
    void lookupReturnsBoundChannelId() {
        when(valueOps.get(KEY)).thenReturn(Mono.just("42"));

        StepVerifier.create(router.lookup(7L, "alice:conv-1"))
                .assertNext(id -> assertThat(id).isEqualTo(42L))
                .verifyComplete();
    }

    @Test
    void lookupEmptyWhenNoBinding() {
        when(valueOps.get(KEY)).thenReturn(Mono.empty());

        StepVerifier.create(router.lookup(7L, "alice:conv-1")).verifyComplete();
    }

    @Test
    void lookupEmptyWhenValueNotNumeric() {
        when(valueOps.get(KEY)).thenReturn(Mono.just("not-a-number"));

        StepVerifier.create(router.lookup(7L, "alice:conv-1")).verifyComplete();
    }

    @Test
    void lookupEmptyWhenSessionKeyBlank() {
        StepVerifier.create(router.lookup(7L, "  ")).verifyComplete();
        StepVerifier.create(router.lookup(7L, null)).verifyComplete();
        StepVerifier.create(router.lookup(null, "alice:conv-1")).verifyComplete();
    }

    @Test
    void lookupFailOpenDegradesToEmptyOnRedisError() {
        when(valueOps.get(KEY)).thenReturn(Mono.error(new RuntimeException("redis down")));

        StepVerifier.create(router.lookup(7L, "alice:conv-1")).verifyComplete();
    }

    @Test
    void lookupFailClosedPropagatesError() {
        properties.getSticky().setFailOpen(false);
        when(valueOps.get(KEY)).thenReturn(Mono.error(new RuntimeException("redis down")));

        StepVerifier.create(router.lookup(7L, "alice:conv-1"))
                .verifyError(RuntimeException.class);
    }

    @Test
    void bindWritesWithTtl() {
        when(valueOps.set(KEY, "42", Duration.ofSeconds(120))).thenReturn(Mono.just(true));

        StepVerifier.create(router.bind(7L, "alice:conv-1", 42L)).verifyComplete();
        verify(valueOps).set(KEY, "42", Duration.ofSeconds(120));
    }

    @Test
    void bindFailOpenSwallowsRedisError() {
        when(valueOps.set(KEY, "42", Duration.ofSeconds(120)))
                .thenReturn(Mono.error(new RuntimeException("redis down")));

        StepVerifier.create(router.bind(7L, "alice:conv-1", 42L)).verifyComplete();
    }

    @Test
    void unbindDeletesKey() {
        when(redis.delete(KEY)).thenReturn(Mono.just(1L));

        StepVerifier.create(router.unbind(7L, "alice:conv-1")).verifyComplete();
        verify(redis).delete(KEY);
    }
}
