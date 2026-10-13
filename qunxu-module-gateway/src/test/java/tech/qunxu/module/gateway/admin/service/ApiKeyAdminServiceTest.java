package tech.qunxu.module.gateway.admin.service;

import tech.qunxu.framework.security.core.LoginUser;
import tech.qunxu.framework.security.core.SecurityUtils;
import tech.qunxu.module.gateway.admin.ApiKeyCreated;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.config.ConfigVersionPublisher;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.persistence.entity.ApiKeyEntity;
import tech.qunxu.module.gateway.persistence.mapper.ApiKeyMapper;
import tech.qunxu.module.gateway.persistence.mapper.SystemUserBriefMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ApiKeyAdminService} 单元测试：重点覆盖「管理员 vs 普通用户」的所有权边界与密钥安全约定。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li><b>查询隔离</b>：普通用户只能查到自己的，管理员可查全部/按 userId 过滤；</li>
 *   <li><b>读改删隔离</b>：普通用户访问他人密钥一律 403，管理员可访问任何密钥；</li>
 *   <li><b>归属绑定</b>：普通用户创建时忽略伪造的 userId/userName，管理员可按用户名代建；</li>
 *   <li><b>密钥安全</b>：只存 SHA-256 摘要、明文仅创建/轮换返回一次、密钥内容不可经更新接口修改。</li>
 * </ul>
 */
class ApiKeyAdminServiceTest {

    private ApiKeyMapper apiKeyMapper;
    private SystemUserBriefMapper userMapper;
    private ConfigVersionPublisher publisher;
    private ApiKeyAdminService service;

    @BeforeEach
    void setUp() {
        apiKeyMapper = mock(ApiKeyMapper.class);
        userMapper = mock(SystemUserBriefMapper.class);
        publisher = mock(ConfigVersionPublisher.class);
        when(publisher.publish(any())).thenReturn(reactor.core.publisher.Mono.empty());
        service = new ApiKeyAdminService(apiKeyMapper, userMapper, publisher,
                new GatewayProperties(), new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        SecurityUtils.clear();
    }

    // ---------------------------------------------------------------- 工具

    private static LoginUser loginUser(long id, String username, String... roles) {
        LoginUser user = new LoginUser();
        user.setId(id);
        user.setUsername(username);
        user.setNickname(username);
        user.setTenantId(1L);
        user.setRoleCodes(new HashSet<>(Arrays.asList(roles)));
        return user;
    }

    private void loginAsRegularUser() {
        SecurityUtils.setLoginUser(loginUser(2L, "test", "common"));
    }

    private void loginAsAdmin() {
        SecurityUtils.setLoginUser(loginUser(1L, "admin", "super_admin"));
    }

    private static ApiKeyEntity key(long id, long userId, String userName, String name) {
        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setUserName(userName);
        entity.setName(name);
        entity.setApiKeyHash(sha256Hex("sk-qx-" + id));
        entity.setKeyPrefix("sk-qx-12345678");
        entity.setTenant("1");
        entity.setStatus(1);
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

    // ---------------------------------------------------------------- 查询隔离

    @Test
    void regularUserQueryIsScopedToOwnUserId() {
        loginAsRegularUser();
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ApiKeyEntity>[] wrapperHolder = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper[1];
        when(apiKeyMapper.selectPage(any(), any())).thenAnswer(inv -> {
            wrapperHolder[0] = inv.getArgument(1);
            return inv.getArgument(0);
        });

        // 普通用户即使传入他人 userId 也应被忽略，只查自己的
        service.query("kw", 999L, null, 1, 10).block();

        String sql = wrapperHolder[0].getCustomSqlSegment();
        assertThat(sql).contains("user_id");
        // SQL 参数为占位符，实际值在 paramNameValuePairs：只含本人 2L，不含伪造的 999L
        assertThat(wrapperHolder[0].getParamNameValuePairs().values())
                .contains(2L)
                .doesNotContain(999L);
    }

    @Test
    void adminQueryCanFilterByAnyUserId() {
        loginAsAdmin();
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ApiKeyEntity>[] wrapperHolder = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper[1];
        when(apiKeyMapper.selectPage(any(), any())).thenAnswer(inv -> {
            wrapperHolder[0] = inv.getArgument(1);
            return inv.getArgument(0);
        });

        service.query(null, 5L, null, 1, 10).block();

        // 先触发 SQL 生成（paramNameValuePairs 懒填充），再校验过滤值
        String sql = wrapperHolder[0].getCustomSqlSegment();
        assertThat(sql).contains("user_id");
        assertThat(wrapperHolder[0].getParamNameValuePairs().values()).contains(5L);
    }

    @Test
    void adminQueryWithoutFilterSeesAll() {
        loginAsAdmin();
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ApiKeyEntity>[] wrapperHolder = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper[1];
        when(apiKeyMapper.selectPage(any(), any())).thenAnswer(inv -> {
            wrapperHolder[0] = inv.getArgument(1);
            return inv.getArgument(0);
        });

        service.query(null, null, null, 1, 10).block();

        assertThat(wrapperHolder[0].getCustomSqlSegment()).doesNotContain("user_id");
    }

    @Test
    void queryWithoutLoginRejected() {
        // 无登录用户（不应发生，防御性）
        assertThatThrownBy(() -> service.query(null, null, null, 1, 10))
                .isInstanceOf(GatewayException.class)
                .extracting(e -> ((GatewayException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    // ---------------------------------------------------------------- 读改删隔离

    @Test
    void regularUserCannotReadOthersKey() {
        loginAsRegularUser();
        when(apiKeyMapper.selectById(1L)).thenReturn(key(1L, 1L, "admin", "管理员密钥"));

        StepVerifier.create(service.getById(1L))
                .expectErrorSatisfies(e -> {
                    assertThat(((GatewayException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                })
                .verify();
    }

    @Test
    void adminCanReadAnyKey() {
        loginAsAdmin();
        when(apiKeyMapper.selectById(1L)).thenReturn(key(1L, 1L, "admin", "管理员密钥"));

        ApiKeyEntity entity = service.getById(1L).block();
        assertThat(entity.getId()).isEqualTo(1L);
        assertThat(entity.getUserName()).isEqualTo("admin");
    }

    @Test
    void regularUserCannotUpdateOthersKey() {
        loginAsRegularUser();
        when(apiKeyMapper.selectById(1L)).thenReturn(key(1L, 1L, "admin", "管理员密钥"));

        ApiKeyEntity patch = new ApiKeyEntity();
        patch.setName("改名");
        StepVerifier.create(service.update(1L, patch))
                .expectErrorSatisfies(e -> {
                    assertThat(((GatewayException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                })
                .verify();
    }

    @Test
    void regularUserCannotDeleteOthersKey() {
        loginAsRegularUser();
        when(apiKeyMapper.selectById(1L)).thenReturn(key(1L, 1L, "admin", "管理员密钥"));

        StepVerifier.create(service.delete(1L))
                .expectErrorSatisfies(e -> {
                    assertThat(((GatewayException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                })
                .verify();
    }

    @Test
    void regularUserCanDeleteOwnKey() {
        loginAsRegularUser();
        when(apiKeyMapper.selectById(1L)).thenReturn(key(1L, 2L, "test", "我的密钥"));
        when(apiKeyMapper.deleteById(1L)).thenReturn(1);

        service.delete(1L).block();
        verify(apiKeyMapper).deleteById(1L);
    }

    // ---------------------------------------------------------------- 创建归属

    @Test
    void regularUserCreateBindsToSelfAndStoresOnlyHash() {
        loginAsRegularUser();
        org.mockito.ArgumentCaptor<ApiKeyEntity> captor = org.mockito.ArgumentCaptor.forClass(ApiKeyEntity.class);
        when(apiKeyMapper.insert(any(ApiKeyEntity.class))).thenAnswer(inv -> 1);

        ApiKeyEntity input = new ApiKeyEntity();
        input.setName("生产环境");
        input.setRemark("线上使用");
        ApiKeyCreated created = service.createKey(input).block();

        verify(apiKeyMapper).insert(captor.capture());
        ApiKeyEntity inserted = captor.getValue();
        // 归属绑定为本人
        assertThat(inserted.getUserId()).isEqualTo(2L);
        assertThat(inserted.getUserName()).isEqualTo("test");
        assertThat(inserted.getTenant()).isEqualTo("1");
        // 只存摘要与前缀，明文仅响应返回一次
        assertThat(created.apiKey()).startsWith("sk-qx-");
        assertThat(inserted.getApiKeyHash()).isEqualTo(sha256Hex(created.apiKey()));
        assertThat(inserted.getKeyPrefix()).isEqualTo(created.apiKey().substring(0, 14));
        assertThat(inserted.getApiKeyHash()).isNotEqualTo(created.apiKey());
        assertThat(inserted.getStatus()).isEqualTo(1);
    }

    @Test
    void regularUserCreateIgnoresForgedOwner() {
        loginAsRegularUser();
        org.mockito.ArgumentCaptor<ApiKeyEntity> captor = org.mockito.ArgumentCaptor.forClass(ApiKeyEntity.class);
        when(apiKeyMapper.insert(any(ApiKeyEntity.class))).thenAnswer(inv -> 1);

        ApiKeyEntity input = new ApiKeyEntity();
        input.setName("我的密钥");
        input.setUserId(99L); // 尝试伪造归属
        input.setUserName("hacker");
        service.createKey(input).block();

        verify(apiKeyMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(2L);
        assertThat(captor.getValue().getUserName()).isEqualTo("test");
    }

    @Test
    void adminCreateByUserNameResolvesOwner() {
        loginAsAdmin();
        org.mockito.ArgumentCaptor<ApiKeyEntity> captor = org.mockito.ArgumentCaptor.forClass(ApiKeyEntity.class);
        when(apiKeyMapper.insert(any(ApiKeyEntity.class))).thenAnswer(inv -> 1);
        SystemUserBriefMapper.UserBrief brief = new SystemUserBriefMapper.UserBrief();
        brief.setId(5L);
        brief.setUsername("alice");
        brief.setNickname("爱丽丝");
        brief.setTenantId(1L);
        when(userMapper.selectByUsername(eq("alice"), eq(1L))).thenReturn(brief);

        ApiKeyEntity input = new ApiKeyEntity();
        input.setName("给爱丽丝");
        input.setUserName("alice");
        service.createKey(input).block();

        verify(apiKeyMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(5L);
        assertThat(captor.getValue().getUserName()).isEqualTo("alice");
    }

    @Test
    void adminCreateForUnknownUserRejected() {
        loginAsAdmin();
        when(userMapper.selectByUsername(eq("ghost"), eq(1L))).thenReturn(null);

        ApiKeyEntity input = new ApiKeyEntity();
        input.setName("x");
        input.setUserName("ghost");
        StepVerifier.create(service.createKey(input))
                .expectErrorSatisfies(e -> {
                    assertThat(((GatewayException) e).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                })
                .verify();
    }

    @Test
    void createNameIsRequired() {
        loginAsRegularUser();
        ApiKeyEntity input = new ApiKeyEntity();
        input.setName("  ");
        StepVerifier.create(service.createKey(input))
                .expectErrorSatisfies(e -> {
                    assertThat(((GatewayException) e).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                })
                .verify();
    }

    @Test
    void abstractCreateIsRejected() {
        loginAsRegularUser();
        ApiKeyEntity input = new ApiKeyEntity();
        input.setName("x");
        StepVerifier.create(service.create(input))
                .expectErrorSatisfies(e -> {
                    assertThat(((GatewayException) e).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("一次性返回");
                })
                .verify();
    }

    // ---------------------------------------------------------------- 更新 / 轮换

    @Test
    void updateRejectsChangingKeyContent() {
        loginAsRegularUser();
        when(apiKeyMapper.selectById(1L)).thenReturn(key(1L, 2L, "test", "我的密钥"));

        ApiKeyEntity patch = new ApiKeyEntity();
        patch.setApiKeyHash("abc123");
        StepVerifier.create(service.update(1L, patch))
                .expectErrorSatisfies(e -> {
                    assertThat(((GatewayException) e).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("轮换");
                })
                .verify();
    }

    @Test
    void updateAllowsNameAndStatus() {
        loginAsRegularUser();
        when(apiKeyMapper.selectById(1L)).thenReturn(key(1L, 2L, "test", "我的密钥"));
        when(apiKeyMapper.updateById(any(ApiKeyEntity.class))).thenReturn(1);
        // 更新后重新读取返回
        ApiKeyEntity fresh = key(1L, 2L, "test", "改名后");
        fresh.setStatus(0);
        when(apiKeyMapper.selectById(1L)).thenReturn(fresh);

        ApiKeyEntity patch = new ApiKeyEntity();
        patch.setName("改名后");
        patch.setStatus(0);
        // 合并后归属/摘要字段被清空，不允许进入 SET
        ApiKeyEntity saved = service.update(1L, patch).block();
        assertThat(saved.getName()).isEqualTo("改名后");
        assertThat(saved.getStatus()).isEqualTo(0);

        org.mockito.ArgumentCaptor<ApiKeyEntity> captor = org.mockito.ArgumentCaptor.forClass(ApiKeyEntity.class);
        verify(apiKeyMapper).updateById(captor.capture());
        assertThat(captor.getValue().getApiKeyHash()).isNull();
        assertThat(captor.getValue().getKeyPrefix()).isNull();
        assertThat(captor.getValue().getUserId()).isNull();
    }

    @Test
    void rotateReturnsNewPlaintextAndReplacesHash() {
        loginAsRegularUser();
        ApiKeyEntity existing = key(1L, 2L, "test", "我的密钥");
        String oldHash = existing.getApiKeyHash();
        when(apiKeyMapper.selectById(1L)).thenReturn(existing);
        when(apiKeyMapper.updateById(any(ApiKeyEntity.class))).thenReturn(1);

        ApiKeyCreated created = service.rotate(1L).block();

        assertThat(created.apiKey()).startsWith("sk-qx-");
        assertThat(created.key().getApiKeyHash()).isEqualTo(sha256Hex(created.apiKey()));
        assertThat(created.key().getApiKeyHash()).isNotEqualTo(oldHash);
        // 轮换后清除最近使用时间
        assertThat(created.key().getLastUsedAt()).isNull();

        org.mockito.ArgumentCaptor<ApiKeyEntity> captor = org.mockito.ArgumentCaptor.forClass(ApiKeyEntity.class);
        verify(apiKeyMapper).updateById(captor.capture());
        assertThat(captor.getValue().getApiKeyHash()).isEqualTo(sha256Hex(created.apiKey()));
    }

    @Test
    void rotateOthersKeyRejected() {
        loginAsRegularUser();
        when(apiKeyMapper.selectById(1L)).thenReturn(key(1L, 1L, "admin", "管理员密钥"));

        StepVerifier.create(service.rotate(1L))
                .expectErrorSatisfies(e -> {
                    assertThat(((GatewayException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                })
                .verify();
    }

    // ---------------------------------------------------------------- 过期时间

    @Test
    void createRejectsPastExpiry() {
        loginAsRegularUser();
        ApiKeyEntity input = new ApiKeyEntity();
        input.setName("x");
        input.setExpiresAt(LocalDateTime.now().minusDays(1));
        StepVerifier.create(service.createKey(input))
                .expectErrorSatisfies(e -> {
                    assertThat(((GatewayException) e).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                })
                .verify();
    }
}
