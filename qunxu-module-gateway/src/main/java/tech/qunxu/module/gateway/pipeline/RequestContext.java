package tech.qunxu.module.gateway.pipeline;

import tech.qunxu.module.gateway.common.enums.CallStatus;
import tech.qunxu.module.gateway.common.enums.Capability;
import tech.qunxu.module.gateway.common.enums.ProtocolType;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.model.TokenUsage;
import tech.qunxu.module.gateway.common.model.UnifiedRequest;
import tech.qunxu.module.gateway.common.model.UnifiedResponse;
import tech.qunxu.module.gateway.common.model.UnifiedStreamChunk;
import tech.qunxu.module.gateway.persistence.entity.LogicalModelEntity;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import tech.qunxu.module.gateway.security.CallerIdentity;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求上下文：贯穿整条流水线的可变载体。
 *
 * <p>承载请求 IR、上游协议、调用人、模型解析结果、选中通道/供应商、响应、token 用量、
 * 耗时、审计标记等。各 {@link PipelineFilter} 读取并逐步填充。线程模型为「单请求内串行」，
 * 但流式回调可能在不同线程触发，故 {@code attributes} 使用并发容器。</p>
 */
public final class RequestContext {

    /** 属性键：原始 {@code HttpServletRequest}，供鉴权等需要读取 principal/请求头的过滤器使用。 */
    public static final String ATTR_REQUEST = "gw.httpServletRequest";
    /** 属性键：原始 {@code HttpServletResponse}，供限流等需要写入响应头的过滤器使用。 */
    public static final String ATTR_RESPONSE = "gw.httpServletResponse";

    // ---- 追踪与请求元信息 ----
    private String traceId;
    private ProtocolType protocol;
    private String method;
    private String path;
    private String endpoint;
    private String clientIp;
    private CallerIdentity caller = CallerIdentity.anonymous();

    // ---- 请求 IR（可被 ModelResolve/Guardrail 改写） ----
    private UnifiedRequest request;

    // ---- 模型解析结果 ----
    private Long logicalModelId;
    private String logicalModelName;
    private Capability capability;
    /** 解析出的逻辑模型实体，供路由选择通道与负载均衡使用。 */
    private LogicalModelEntity logicalModel;

    // ---- 选中的通道与供应商（Route 阶段填充） ----
    private ModelChannelEntity selectedChannel;
    private ProviderEntity selectedProvider;

    // ---- 响应与用量 ----
    private UnifiedResponse response;
    /** 流式结果（冷流，由 InvokeFilter 设置，控制器订阅渲染为 SSE）。 */
    private Flux<UnifiedStreamChunk> streamResult;
    private TokenUsage usage = TokenUsage.empty();

    // ---- 计时 ----
    private final long startNanos = System.nanoTime();
    private Long firstTokenNanos;

    // ---- 结果标记（供审计） ----
    private CallStatus status;
    private ErrorCode errorCode;
    private String errorMessage;
    private String guardrailHit;
    private String moderationResult;

    // ---- 可逆脱敏：占位符 → 原文 映射（仅输入围栏占位符模式产生；供输出阶段本地还原，不落审计） ----
    private Map<String, String> redactionMap;

    // ---- 会话级粘性：客户端透传的会话 ID（Auth 阶段解析请求头；供路由阶段做会话粘性） ----
    private String sessionId;

    // ---- 审计辅助：原始请求体与聚合响应文本（有界） ----
    /** 流式响应聚合文本硬上限；审计再按 {@code maxBodyLength} 进一步截断。 */
    private static final int RESPONSE_TEXT_CAP = 65_536;
    /** 上游原始请求体 JSON（由编排器写入，供审计）。 */
    private String rawRequestBody;
    private final StringBuilder responseText = new StringBuilder();

    // ---- 过滤器间共享的自由属性 ----
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    private RequestContext() {
    }

    /** 创建一个上下文。 */
    public static RequestContext create(ProtocolType protocol, String method, String path,
                                        String endpoint, String traceId, UnifiedRequest request) {
        RequestContext ctx = new RequestContext();
        ctx.protocol = protocol;
        ctx.method = method;
        ctx.path = path;
        ctx.endpoint = endpoint;
        ctx.traceId = traceId;
        ctx.request = request;
        if (request != null) {
            ctx.capability = request.capability();
        }
        return ctx;
    }

    // ---- 计时辅助 ----

    /** 标记首字（首个流式增量）到达时刻，仅记录一次。 */
    public void markFirstToken() {
        if (firstTokenNanos == null) {
            firstTokenNanos = System.nanoTime();
        }
    }

    /** 从请求开始到现在的毫秒数。 */
    public long elapsedMs() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** 首字延迟毫秒数，未产生流式增量时为 {@code null}。 */
    public Long firstTokenMs() {
        return firstTokenNanos == null ? null : (firstTokenNanos - startNanos) / 1_000_000L;
    }

    // ---- 属性辅助 ----

    public void setAttribute(String key, Object value) {
        if (key != null && value != null) {
            attributes.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T attribute(String key) {
        return (T) attributes.get(key);
    }

    public boolean hasAttribute(String key) {
        return attributes.containsKey(key);
    }

    // ---- 审计辅助 ----

    public String getRawRequestBody() { return rawRequestBody; }
    public void setRawRequestBody(String rawRequestBody) { this.rawRequestBody = rawRequestBody; }

    /** 追加流式响应增量文本（有界、线程安全）；超出硬上限后丢弃后续增量。 */
    public void appendResponseText(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        synchronized (responseText) {
            int remain = RESPONSE_TEXT_CAP - responseText.length();
            if (remain <= 0) {
                return;
            }
            responseText.append(delta, 0, Math.min(delta.length(), remain));
        }
    }

    /** 已聚合的流式响应文本；非流式或无增量时为空串。 */
    public String responseText() {
        synchronized (responseText) {
            return responseText.toString();
        }
    }

    // ---- getters / setters ----

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public ProtocolType getProtocol() { return protocol; }
    public void setProtocol(ProtocolType protocol) { this.protocol = protocol; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getClientIp() { return clientIp; }
    public void setClientIp(String clientIp) { this.clientIp = clientIp; }
    public CallerIdentity getCaller() { return caller; }
    public void setCaller(CallerIdentity caller) { this.caller = caller != null ? caller : CallerIdentity.anonymous(); }
    public UnifiedRequest getRequest() { return request; }
    public void setRequest(UnifiedRequest request) { this.request = request; }
    public Long getLogicalModelId() { return logicalModelId; }
    public void setLogicalModelId(Long logicalModelId) { this.logicalModelId = logicalModelId; }
    public String getLogicalModelName() { return logicalModelName; }
    public void setLogicalModelName(String logicalModelName) { this.logicalModelName = logicalModelName; }
    public Capability getCapability() { return capability; }
    public void setCapability(Capability capability) { this.capability = capability; }
    public LogicalModelEntity getLogicalModel() { return logicalModel; }
    public void setLogicalModel(LogicalModelEntity logicalModel) { this.logicalModel = logicalModel; }
    public ModelChannelEntity getSelectedChannel() { return selectedChannel; }
    public void setSelectedChannel(ModelChannelEntity selectedChannel) { this.selectedChannel = selectedChannel; }
    public ProviderEntity getSelectedProvider() { return selectedProvider; }
    public void setSelectedProvider(ProviderEntity selectedProvider) { this.selectedProvider = selectedProvider; }
    public UnifiedResponse getResponse() { return response; }
    public void setResponse(UnifiedResponse response) { this.response = response; }
    public Flux<UnifiedStreamChunk> getStreamResult() { return streamResult; }
    public void setStreamResult(Flux<UnifiedStreamChunk> streamResult) { this.streamResult = streamResult; }
    public TokenUsage getUsage() { return usage; }
    public void setUsage(TokenUsage usage) { this.usage = usage != null ? usage : TokenUsage.empty(); }
    public CallStatus getStatus() { return status; }
    public void setStatus(CallStatus status) { this.status = status; }
    public ErrorCode getErrorCode() { return errorCode; }
    public void setErrorCode(ErrorCode errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getGuardrailHit() { return guardrailHit; }
    public void setGuardrailHit(String guardrailHit) { this.guardrailHit = guardrailHit; }
    public String getModerationResult() { return moderationResult; }
    public void setModerationResult(String moderationResult) { this.moderationResult = moderationResult; }
    public Map<String, String> getRedactionMap() { return redactionMap; }
    public void setRedactionMap(Map<String, String> redactionMap) { this.redactionMap = redactionMap; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Map<String, Object> getAttributes() { return attributes; }
}
