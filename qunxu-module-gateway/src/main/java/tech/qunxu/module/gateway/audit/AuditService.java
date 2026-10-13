package tech.qunxu.module.gateway.audit;

import tech.qunxu.module.gateway.common.enums.CallStatus;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.model.TokenUsage;
import tech.qunxu.module.gateway.common.model.UnifiedResponse;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.health.PassiveHealthMonitor;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import tech.qunxu.module.gateway.pipeline.RequestContext;
import tech.qunxu.module.gateway.security.CallerIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.SignalType;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 审计服务：在请求结束时从 {@link RequestContext} 组装 {@link AuditRecord} 并提交 {@link BatchAuditWriter}。
 *
 * <p>状态派生：优先采用上下文中已显式设置的状态（如安全围栏的 BLOCKED），否则成功完成记 SUCCESS、
 * 其余（错误/取消）记 FAILURE。请求/响应体按 {@code gateway.audit.max-body-length} 截断，并在
 * {@code mask-sensitive=true} 时对疑似凭证做脱敏。组装/提交全程「尽力而为」，绝不抛出影响主链。</p>
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int MAX_ERROR_MSG = 1024;

    /**
     * 疑似凭证的脱敏模式与替换（按序应用）。
     * <p>模式 0 的值部分吞掉可选的 {@code Bearer } 前缀，避免 {@code authorization: Bearer <token>}
     * 只掩掉 {@code Bearer} 而泄露真实令牌；模式 1 兜底独立的 {@code Bearer <token>}；模式 2 兜底 {@code sk-} 密钥。</p>
     */
    private static final Pattern[] SECRET_PATTERNS = {
            Pattern.compile("(?i)(\"?(?:api[_-]?key|apikey|authorization|password|passwd|secret|access[_-]?token|refresh[_-]?token)\"?\\s*[:=]\\s*\"?)(?:Bearer\\s+)?[^\",}\\s]+"),
            Pattern.compile("\\bBearer\\s+[A-Za-z0-9._\\-]+"),
            Pattern.compile("\\bsk-[A-Za-z0-9_\\-]{6,}")
    };
    private static final String[] SECRET_REPLACEMENTS = {"$1****", "Bearer ****", "sk-****"};

    private final BatchAuditWriter writer;
    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;

    public AuditService(BatchAuditWriter writer, GatewayProperties properties, ObjectMapper objectMapper) {
        this.writer = writer;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled() {
        return properties.getAudit().isEnabled();
    }

    /** 捕获链路错误到上下文（不覆盖已显式设置的 BLOCKED 等状态）。 */
    public void captureError(RequestContext ctx, Throwable err) {
        if (ctx == null || err == null) {
            return;
        }
        if (ctx.getStatus() == null) {
            ctx.setStatus(CallStatus.FAILURE);
        }
        if (ctx.getErrorCode() == null) {
            ErrorCode code = PassiveHealthMonitor.errorCodeOf(err);
            ctx.setErrorCode(code != null ? code : ErrorCode.INTERNAL_ERROR);
        }
        if (ctx.getErrorMessage() == null) {
            ctx.setErrorMessage(err.getMessage());
        }
    }

    /** 组装并提交审计记录（在 {@code doFinally} 中调用，绝不抛出）。 */
    public void record(RequestContext ctx, SignalType signal) {
        if (ctx == null || !isEnabled()) {
            return;
        }
        try {
            writer.submit(build(ctx, signal));
        } catch (RuntimeException e) {
            log.warn("组装审计记录失败: {}", e.toString());
        }
    }

    AuditRecord build(RequestContext ctx, SignalType signal) {
        int maxBody = Math.max(0, properties.getAudit().getMaxBodyLength());
        boolean mask = properties.getAudit().isMaskSensitive();

        CallStatus status = ctx.getStatus() != null ? ctx.getStatus()
                : (signal == SignalType.ON_COMPLETE ? CallStatus.SUCCESS : CallStatus.FAILURE);
        ErrorCode errorCode = ctx.getErrorCode();
        String errorMsg = ctx.getErrorMessage();
        if (status != CallStatus.SUCCESS && errorMsg == null && signal == SignalType.CANCEL) {
            errorMsg = "客户端取消/断开";
        }

        CallerIdentity caller = ctx.getCaller();
        TokenUsage usage = ctx.getUsage() != null ? ctx.getUsage() : TokenUsage.empty();
        ModelChannelEntity channel = ctx.getSelectedChannel();
        ProviderEntity provider = ctx.getSelectedProvider();
        String requestBody = prepare(ctx.getRawRequestBody(), mask, maxBody);
        String responseBody = prepare(responseBody(ctx), mask, maxBody);

        return new AuditRecord(
                ctx.getTraceId(),
                caller != null ? caller.callerId() : null,
                caller != null ? caller.callerName() : null,
                caller != null ? caller.tenant() : null,
                ctx.getClientIp(),
                ctx.getProtocol() != null ? ctx.getProtocol().name() : null,
                ctx.getEndpoint(),
                logicalModelName(ctx),
                channel != null ? channel.getId() : null,
                provider != null ? provider.getId() : (channel != null ? channel.getProviderId() : null),
                provider != null ? provider.getCode() : null,
                channel != null ? channel.getUpstreamModel() : null,
                requestBody,
                responseBody,
                nz(usage.promptTokens()),
                nz(usage.completionTokens()),
                nz(usage.totalTokens()),
                ctx.elapsedMs(),
                ctx.firstTokenMs(),
                status,
                errorCode != null ? errorCode.name() : null,
                truncate(errorMsg, MAX_ERROR_MSG),
                ctx.getGuardrailHit(),
                ctx.getModerationResult(),
                LocalDateTime.now());
    }

    private String responseBody(RequestContext ctx) {
        UnifiedResponse resp = ctx.getResponse();
        if (resp != null) {
            try {
                return objectMapper.writeValueAsString(resp);
            } catch (RuntimeException e) {
                log.debug("序列化响应体失败，跳过响应体审计: {}", e.toString());
                return null;
            }
        }
        String text = ctx.responseText();
        return (text == null || text.isEmpty()) ? null : text;
    }

    private String logicalModelName(RequestContext ctx) {
        String name = ctx.getLogicalModelName();
        if (name == null && ctx.getLogicalModel() != null) {
            name = ctx.getLogicalModel().getName();
        }
        if (name == null && ctx.getRequest() != null) {
            name = ctx.getRequest().model();
        }
        return name;
    }

    private static String prepare(String text, boolean mask, int maxBody) {
        String out = mask ? maskSecrets(text) : text;
        return truncate(out, maxBody);
    }

    private static int nz(Integer v) {
        return v != null ? v : 0;
    }

    static String maskSecrets(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (int i = 0; i < SECRET_PATTERNS.length; i++) {
            out = SECRET_PATTERNS[i].matcher(out).replaceAll(SECRET_REPLACEMENTS[i]);
        }
        return out;
    }

    static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        if (max <= 0 || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...[truncated " + (text.length() - max) + " chars]";
    }
}
