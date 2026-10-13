package tech.qunxu.module.gateway.admin;

import tech.qunxu.framework.security.core.LoginUser;
import tech.qunxu.framework.security.core.SecurityUtils;
import tech.qunxu.module.gateway.audit.AuditPage;
import tech.qunxu.module.gateway.audit.AuditQuery;
import tech.qunxu.module.gateway.audit.AuditQueryService;
import tech.qunxu.module.gateway.audit.TokenUsageStat;
import tech.qunxu.module.gateway.audit.UsageDimension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuditAdminController} 单元测试：验证「普通用户只能查本人审计/用量」的强制隔离。
 *
 * <p>控制器在方法入口（Servlet 线程）解析登录用户并修正 {@code callerId}，因此可直接
 * 以 {@link SecurityUtils#setLoginUser} 构造安全上下文，捕获传给 {@link AuditQueryService}
 * 的 {@link AuditQuery} 断言过滤条件。</p>
 */
class AuditAdminControllerTest {

    private AuditQueryService queryService;
    private AuditAdminController controller;

    @BeforeEach
    void setUp() {
        queryService = mock(AuditQueryService.class);
        when(queryService.query(any())).thenReturn(Mono.just(new AuditPage(List.of(), 0L, 1, 20)));
        when(queryService.usage(any(), any())).thenReturn(Mono.just(List.<TokenUsageStat>of()));
        controller = new AuditAdminController(queryService);
    }

    @AfterEach
    void tearDown() {
        SecurityUtils.clear();
    }

    private static LoginUser loginUser(long id, String username, String... roles) {
        LoginUser user = new LoginUser();
        user.setId(id);
        user.setUsername(username);
        user.setNickname(username);
        user.setTenantId(1L);
        user.setRoleCodes(new HashSet<>(Arrays.asList(roles)));
        return user;
    }

    private static LoginUser loginUserNoId(String username, String... roles) {
        LoginUser user = new LoginUser();
        user.setUsername(username);
        user.setNickname(username);
        user.setTenantId(1L);
        user.setRoleCodes(new HashSet<>(Arrays.asList(roles)));
        return user;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<AuditQuery> queryCaptor() {
        return ArgumentCaptor.forClass(AuditQuery.class);
    }

    // ---------------------------------------------------------------- 分页查询隔离

    @Test
    void regularUserQueryForcesOwnCallerId() {
        SecurityUtils.setLoginUser(loginUser(2L, "test", "common"));

        // 普通用户即使伪造他人 callerId 也会被替换为本人 id
        controller.query("999", null, null, null, null, null, null, null, null,
                null, null, 1, 20).block();

        ArgumentCaptor<AuditQuery> captor = queryCaptor();
        verify(queryService).query(captor.capture());
        assertThat(captor.getValue().callerId()).isEqualTo("2");
    }

    @Test
    void regularUserQueryWithoutCallerIdStillScoped() {
        SecurityUtils.setLoginUser(loginUser(2L, "test", "common"));

        controller.query(null, null, null, null, null, null, null, null, null,
                null, null, 1, 20).block();

        ArgumentCaptor<AuditQuery> captor = queryCaptor();
        verify(queryService).query(captor.capture());
        assertThat(captor.getValue().callerId()).isEqualTo("2");
    }

    @Test
    void regularUserQueryFallsBackToUsernameWhenIdMissing() {
        SecurityUtils.setLoginUser(loginUserNoId("test", "common"));

        controller.query(null, null, null, null, null, null, null, null, null,
                null, null, 1, 20).block();

        ArgumentCaptor<AuditQuery> captor = queryCaptor();
        verify(queryService).query(captor.capture());
        assertThat(captor.getValue().callerId()).isEqualTo("test");
    }

    @Test
    void adminQueryKeepsRequestedCallerId() {
        SecurityUtils.setLoginUser(loginUser(1L, "admin", "super_admin"));

        controller.query("alice", null, null, null, null, null, null, null, null,
                null, null, 1, 20).block();

        ArgumentCaptor<AuditQuery> captor = queryCaptor();
        verify(queryService).query(captor.capture());
        assertThat(captor.getValue().callerId()).isEqualTo("alice");
    }

    @Test
    void adminQueryWithoutCallerIdSeesAll() {
        SecurityUtils.setLoginUser(loginUser(1L, "admin", "super_admin"));

        controller.query(null, null, null, null, null, null, null, null, null,
                null, null, 1, 20).block();

        ArgumentCaptor<AuditQuery> captor = queryCaptor();
        verify(queryService).query(captor.capture());
        assertThat(captor.getValue().callerId()).isNull();
    }

    @Test
    void unauthenticatedQueryKeepsRequestedCallerId() {
        // 无登录用户（不应发生，防御性）：原样透传
        controller.query("alice", null, null, null, null, null, null, null, null,
                null, null, 1, 20).block();

        ArgumentCaptor<AuditQuery> captor = queryCaptor();
        verify(queryService).query(captor.capture());
        assertThat(captor.getValue().callerId()).isEqualTo("alice");
    }

    // ---------------------------------------------------------------- 用量聚合隔离

    @Test
    void regularUserUsageForcesOwnCallerId() {
        SecurityUtils.setLoginUser(loginUser(2L, "test", "common"));

        controller.usage("day", "999", null, null, null, null, null, null, null, null,
                null, null).block();

        ArgumentCaptor<AuditQuery> captor = queryCaptor();
        verify(queryService).usage(any(), captor.capture());
        assertThat(captor.getValue().callerId()).isEqualTo("2");
    }

    @Test
    void adminUsageKeepsRequestedCallerId() {
        SecurityUtils.setLoginUser(loginUser(1L, "admin", "super_admin"));

        controller.usage("caller", "bob", null, null, null, null, null, null, null, null,
                null, null).block();

        ArgumentCaptor<AuditQuery> captor = queryCaptor();
        verify(queryService).usage(any(), captor.capture());
        assertThat(captor.getValue().callerId()).isEqualTo("bob");
    }

    @Test
    void adminUsageWithDayDimensionAndNoCallerIdSeesAll() {
        SecurityUtils.setLoginUser(loginUser(1L, "admin", "super_admin"));

        controller.usage("day", null, null, null, null, null, null, null, null, null,
                null, null).block();

        ArgumentCaptor<AuditQuery> captor = queryCaptor();
        verify(queryService).usage(eq(UsageDimension.DAY), captor.capture());
        assertThat(captor.getValue().callerId()).isNull();
    }
}
