package tech.qunxu.module.gateway.security;

import tech.qunxu.module.gateway.persistence.entity.ApiKeyEntity;
import tech.qunxu.module.gateway.persistence.mapper.ApiKeyMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ApiKeySnapshotService} 单元测试：快照载入/重建、摘要匹配、停用与过期过滤、故障降级。
 */
class ApiKeySnapshotServiceTest {

    private static final String VALID_KEY = "sk-qx-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String DISABLED_KEY = "sk-qx-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String EXPIRED_KEY = "sk-qx-cccccccccccccccccccccccccccccccc";

    private ApiKeyEntity key(String plain, int status, LocalDateTime expiresAt) {
        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setId(1L);
        entity.setUserId(2L);
        entity.setUserName("test");
        entity.setName("测试密钥");
        entity.setApiKeyHash(sha256Hex(plain));
        entity.setKeyPrefix(plain.substring(0, 14));
        entity.setTenant("1");
        entity.setStatus(status);
        entity.setExpiresAt(expiresAt);
        return entity;
    }

    private ApiKeySnapshotService serviceWith(ApiKeyEntity... keys) {
        ApiKeyMapper mapper = mock(ApiKeyMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(keys));
        return new ApiKeySnapshotService(mapper);
    }

    @Test
    void refreshLoadsEnabledKeysAndExcludesDisabledAtLoad() {
        ApiKeySnapshotService service = serviceWith(
                key(VALID_KEY, 1, null),
                key(DISABLED_KEY, 0, null),
                key(EXPIRED_KEY, 1, LocalDateTime.now().minusDays(1)));
        service.refresh().block();

        // 停用键在载入时剔除（代码层双保险）；过期键由 SQL 过滤，快照层靠运行期 resolve 校验
        assertThat(service.size()).isEqualTo(2);
        assertThat(service.resolve(VALID_KEY)).isPresent();
        assertThat(service.resolve(DISABLED_KEY)).isEmpty();
        assertThat(service.resolve(EXPIRED_KEY)).isEmpty();
    }

    @Test
    void resolveMatchesByHashAndCarriesOwnerMetadata() {
        ApiKeySnapshotService service = serviceWith(key(VALID_KEY, 1, null));
        service.refresh().block();

        Optional<ApiKeySnapshotService.ApiKeyAuth> auth = service.resolve(VALID_KEY);
        assertThat(auth).isPresent();
        assertThat(auth.get().userId()).isEqualTo(2L);
        assertThat(auth.get().userName()).isEqualTo("test");
        assertThat(auth.get().tenant()).isEqualTo("1");
    }

    @Test
    void resolveUnknownOrBlankReturnsEmpty() {
        ApiKeySnapshotService service = serviceWith(key(VALID_KEY, 1, null));
        service.refresh().block();

        assertThat(service.resolve("sk-qx-unknown000000000000000000000000")).isEmpty();
        assertThat(service.resolve("")).isEmpty();
        assertThat(service.resolve(null)).isEmpty();
    }

    @Test
    void refreshFailureKeepsExistingSnapshot() {
        ApiKeyMapper mapper = mock(ApiKeyMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(key(VALID_KEY, 1, null)));
        ApiKeySnapshotService service = new ApiKeySnapshotService(mapper);
        service.refresh().block();
        assertThat(service.size()).isEqualTo(1);

        // 数据库故障：重建失败应保持现有快照，不抛异常
        when(mapper.selectList(any())).thenThrow(new RuntimeException("db down"));
        Integer size = service.refresh().block();
        assertThat(size).isEqualTo(1);
        assertThat(service.resolve(VALID_KEY)).isPresent();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
