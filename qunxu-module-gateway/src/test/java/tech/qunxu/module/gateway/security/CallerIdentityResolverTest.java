package tech.qunxu.module.gateway.security;

import tech.qunxu.framework.security.core.LoginUser;
import tech.qunxu.framework.security.core.SecurityUtils;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.persistence.entity.ApiKeyEntity;
import tech.qunxu.module.gateway.persistence.mapper.ApiKeyMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CallerIdentityResolver} 单元测试：覆盖登录用户、网关 APIKey、
 * 静态令牌回退与匿名四种路径。不依赖 Spring 上下文与真实数据库。
 */
class CallerIdentityResolverTest {

    @AfterEach
    void tearDown() {
        SecurityUtils.clear();
    }

    private CallerIdentityResolver newResolver(String staticTokens, ApiKeySnapshotService apiKeyService) {
        GatewayProperties props = new GatewayProperties();
        props.getSecurity().setStaticTokens(staticTokens);
        return new CallerIdentityResolver(props, apiKeyService);
    }

    /** 构建一个快照包含给定密钥的解析器（快照从 mock Mapper 载入）。 */
    private ApiKeySnapshotService snapshotService(ApiKeyEntity... keys) {
        ApiKeyMapper mapper = mock(ApiKeyMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(keys));
        ApiKeySnapshotService service = new ApiKeySnapshotService(mapper);
        service.refresh().block();
        return service;
    }

    private static ApiKeyEntity key(long id, long userId, String userName, String name,
                                    String plain, int status, LocalDateTime expiresAt) {
        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setUserName(userName);
        entity.setName(name);
        entity.setApiKeyHash(sha256Hex(plain));
        entity.setKeyPrefix(plain.length() > 16 ? plain.substring(0, 16) : plain);
        entity.setTenant("1");
        entity.setStatus(status);
        entity.setExpiresAt(expiresAt);
        return entity;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void resolvesLoginUserPrincipal() {
        LoginUser user = new LoginUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setNickname("管理员");
        user.setTenantId(1L);
        user.setPermissions(Set.of("gateway:provider:query"));
        SecurityUtils.setLoginUser(user);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");

        CallerIdentity id = newResolver("", snapshotService()).resolve(request);
        assertThat(id.authenticated()).isTrue();
        assertThat(id.callerId()).isEqualTo("1");
        assertThat(id.callerName()).isEqualTo("管理员");
        assertThat(id.tenant()).isEqualTo("1");
        assertThat(id.scopes()).contains("gateway:invoke", "gateway:provider:query");
    }

    @Test
    void resolvesApiKeyToOwnerIdentity() {
        ApiKeyEntity key = key(10L, 2L, "test", "生产环境", "sk-qx-0123456789abcdef0123456789abcdef",
                1, null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/chat/completions");
        request.addHeader("Authorization", "Bearer sk-qx-0123456789abcdef0123456789abcdef");

        CallerIdentity id = newResolver("", snapshotService(key)).resolve(request);
        assertThat(id.authenticated()).isTrue();
        assertThat(id.callerId()).isEqualTo("2");
        assertThat(id.callerName()).isEqualTo("test");
        assertThat(id.tenant()).isEqualTo("1");
        assertThat(id.attributes())
                .containsEntry("auth", "api-key")
                .containsEntry("apiKeyId", 10L)
                .containsEntry("apiKeyName", "生产环境");
    }

    @Test
    void disabledApiKeyIsNotResolved() {
        ApiKeyEntity key = key(11L, 2L, "test", "停用密钥", "sk-qx-disabled0000000000000000000000",
                0, null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
        request.addHeader("Authorization", "Bearer sk-qx-disabled0000000000000000000000");

        CallerIdentity id = newResolver("", snapshotService(key)).resolve(request);
        assertThat(id.authenticated()).isFalse();
        assertThat(id.auditId()).isEqualTo("anonymous");
    }

    @Test
    void expiredApiKeyIsNotResolved() {
        ApiKeyEntity key = key(12L, 2L, "test", "已过期", "sk-qx-expired00000000000000000000000",
                1, LocalDateTime.now().minusDays(1));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
        request.addHeader("Authorization", "Bearer sk-qx-expired00000000000000000000000");

        CallerIdentity id = newResolver("", snapshotService(key)).resolve(request);
        assertThat(id.authenticated()).isFalse();
    }

    @Test
    void unknownApiKeyFallsBackToAnonymous() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
        request.addHeader("Authorization", "Bearer sk-qx-unknown000000000000000000000000");

        CallerIdentity id = newResolver("", snapshotService()).resolve(request);
        assertThat(id.authenticated()).isFalse();
    }

    @Test
    void resolvesStaticTokenWhenNotLoggedIn() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
        request.addHeader("Authorization", "Bearer test-token-alice");

        CallerIdentity id = newResolver("test-token-alice:alice", snapshotService()).resolve(request);
        assertThat(id.authenticated()).isTrue();
        assertThat(id.callerId()).isEqualTo("alice");
        assertThat(id.tenant()).isEqualTo("default");
        assertThat(id.auditId()).isEqualTo("alice");
    }

    @Test
    void anonymousWhenNotLoggedInAndNoToken() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");

        CallerIdentity id = newResolver("", snapshotService()).resolve(request);
        assertThat(id.authenticated()).isFalse();
        assertThat(id.callerId()).isNull();
        assertThat(id.tenant()).isEqualTo("default");
        assertThat(id.auditId()).isEqualTo("anonymous");
    }
}
