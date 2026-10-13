package tech.qunxu.module.gateway.admin;

import tech.qunxu.framework.security.core.LoginUser;
import tech.qunxu.framework.security.core.SecurityUtils;
import tech.qunxu.module.gateway.admin.service.ApiKeyAdminService;
import tech.qunxu.module.gateway.audit.AuditPage;
import tech.qunxu.module.gateway.audit.AuditQuery;
import tech.qunxu.module.gateway.audit.AuditQueryService;
import tech.qunxu.module.gateway.audit.TokenUsageStat;
import tech.qunxu.module.gateway.audit.UsageDimension;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计查询管理接口（{@code /admin-api/gateway/audit}）。供运维/管理后台按多维条件检索调用审计与查看 Token 用量聚合。
 *
 * <p>权限模型（与 {@link ApiKeyAdminService} 一致，由本控制器在方法入口强制执行）：</p>
 * <ul>
 *   <li><b>管理员</b>（super_admin 角色）：可按任意 {@code callerId} 等条件查询全部审计与用量；</li>
 *   <li><b>普通用户</b>：只能查询<b>本人</b>（登录用户 id 对应的 callerId）的审计与用量，
 *       请求中伪造的 {@code callerId} 会被忽略并强制替换为本人 id。</li>
 * </ul>
 *
 * <p>受 {@code SecurityConfig} 保护：{@code gateway.security.enabled=true} 时 {@code /admin/**} 需认证。
 * 时间参数接受 ISO-8601（如 {@code 2026-09-10T00:00:00}）。分页大小封顶见 {@link AuditQuery#MAX_SIZE}。</p>
 */
@RestController
@RequestMapping("/admin-api/gateway/audit")
public class AuditAdminController {

    private final AuditQueryService queryService;

    public AuditAdminController(AuditQueryService queryService) {
        this.queryService = queryService;
    }

    /** 多维过滤分页查询审计记录（按创建时间倒序）。 */
    @GetMapping
    public Mono<AuditPage> query(
            @RequestParam(required = false) String callerId,
            @RequestParam(required = false) String tenant,
            @RequestParam(required = false) String logicalModel,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long channelId,
            @RequestParam(required = false) Long providerId,
            @RequestParam(required = false) String errorCode,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        AuditQuery query = buildQuery(scopedCallerId(callerId), tenant, logicalModel, protocol, status,
                channelId, providerId, errorCode, traceId, from, to, page, size);
        return queryService.query(query);
    }

    /**
     * 按维度聚合 Token 用量。
     *
     * @param dimension 聚合维度 {@code caller}/{@code model}/{@code day}（缺省为 {@code day}）
     */
    @GetMapping("/usage")
    public Mono<List<TokenUsageStat>> usage(
            @RequestParam(required = false) String dimension,
            @RequestParam(required = false) String callerId,
            @RequestParam(required = false) String tenant,
            @RequestParam(required = false) String logicalModel,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long channelId,
            @RequestParam(required = false) Long providerId,
            @RequestParam(required = false) String errorCode,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        AuditQuery query = buildQuery(scopedCallerId(callerId), tenant, logicalModel, protocol, status,
                channelId, providerId, errorCode, traceId, from, to, null, null);
        return queryService.usage(UsageDimension.of(dimension), query);
    }

    /**
     * 解析查询所用 {@code callerId}：普通用户强制为本人（登录用户 id 的字符串形式），
     * 管理员/未登录用户原样透传。必须在方法入口（Servlet 线程）调用，避免响应式线程
     * 拿不到安全上下文。
     */
    private static String scopedCallerId(String requested) {
        LoginUser user = SecurityUtils.getLoginUser().orElse(null);
        if (user == null || user.getRoleCodes() == null
                || user.getRoleCodes().contains(ApiKeyAdminService.SUPER_ADMIN_ROLE)) {
            return requested;
        }
        // 普通用户：只看自己的审计记录（callerId 即登录用户 id，见 CallerIdentityResolver）
        return user.getId() != null ? String.valueOf(user.getId()) : user.getUsername();
    }

    private static AuditQuery buildQuery(String callerId, String tenant, String logicalModel, String protocol,
                                         String status, Long channelId, Long providerId, String errorCode,
                                         String traceId, LocalDateTime from, LocalDateTime to,
                                         Integer page, Integer size) {
        return new AuditQuery(callerId, tenant, logicalModel, protocol, status,
                channelId, providerId, errorCode, traceId, from, to, page, size);
    }
}
