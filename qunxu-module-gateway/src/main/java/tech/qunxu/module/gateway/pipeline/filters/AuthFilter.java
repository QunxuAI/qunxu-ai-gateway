package tech.qunxu.module.gateway.pipeline.filters;

import jakarta.servlet.http.HttpServletRequest;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.common.util.WebUtils;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.pipeline.FilterOrder;
import tech.qunxu.module.gateway.pipeline.PipelineChain;
import tech.qunxu.module.gateway.pipeline.PipelineFilter;
import tech.qunxu.module.gateway.pipeline.RequestContext;
import tech.qunxu.module.gateway.security.CallerIdentity;
import tech.qunxu.module.gateway.security.CallerIdentityResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 鉴权过滤器（流水线首环）。
 *
 * <p>解析调用人身份与客户端 IP 写入 {@link RequestContext}；当 {@code gateway.security.enabled=true}
 * 而身份未认证时短路返回 401（正常情况下安全过滤器链已在更早阶段拦截管理面，数据面由本环纵深防御）。</p>
 *
 * <p>由 WebFlux 版（读取 {@code ServerWebExchange}）迁移为 Servlet 版（读取 {@code HttpServletRequest}）。</p>
 */
@Component
public class AuthFilter implements PipelineFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    private final CallerIdentityResolver resolver;
    private final GatewayProperties properties;

    public AuthFilter(CallerIdentityResolver resolver, GatewayProperties properties) {
        this.resolver = resolver;
        this.properties = properties;
    }

    @Override
    public int order() {
        return FilterOrder.AUTH;
    }

    @Override
    public Mono<Void> filter(RequestContext ctx, PipelineChain chain) {
        HttpServletRequest request = ctx.attribute(RequestContext.ATTR_REQUEST);
        CallerIdentity identity = request != null ? resolver.resolve(request) : ctx.getCaller();
        ctx.setCaller(identity);
        if (request != null && ctx.getClientIp() == null) {
            ctx.setClientIp(WebUtils.clientIp(request));
        }
        // 会话级粘性：透传会话 ID 供路由阶段做会话内固定通道（无则跳过粘性，退化为普通路由）
        if (request != null && ctx.getSessionId() == null) {
            String header = properties.getSticky().getHeaderName();
            if (header != null && !header.isBlank()) {
                String sessionId = request.getHeader(header);
                if (sessionId != null && !sessionId.isBlank()) {
                    ctx.setSessionId(sessionId.trim());
                }
            }
        }
        if (properties.getSecurity().isEnabled() && !identity.authenticated()) {
            log.debug("Rejected unauthenticated call to {}", ctx.getPath());
            return Mono.error(new GatewayException(ErrorCode.UNAUTHORIZED));
        }
        if (log.isDebugEnabled()) {
            log.debug("Authenticated caller={} tenant={} session={} for {}",
                    identity.auditId(), identity.tenant(), ctx.getSessionId(), ctx.getPath());
        }
        return chain.execute(ctx);
    }
}
