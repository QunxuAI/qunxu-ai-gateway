package tech.qunxu.module.gateway.pipeline;

/**
 * 过滤器执行顺序常量。数值越小越先执行，段位间预留间隔以便后续插入新过滤器。
 *
 * <pre>
 * [Audit —— 最外层包裹，doFinally 中最后记录]
 *   [Metrics —— 次外层，统计全链路请求量/时延/用量]
 *     Auth ▶ RateLimit ▶ ModelResolve ▶ InputGuardrail ▶ InputModeration
 *          ▶ Route ▶ Invoke ▶ OutputModeration ▶ OutputGuardrail ▶ (终结：写回客户端)
 * </pre>
 */
public final class FilterOrder {

    private FilterOrder() {
    }

    /**
     * 审计包裹过滤器：作为<b>最外层</b>（order 最小）进入，在 {@code doFinally} 中于整条链结束后
     * 组装并异步落库，从而捕获包括鉴权/限流/模型解析/路由等<b>早期失败</b>在内的全部结果。
     * 数值最小使其最先进入、最后记录。
     */
    public static final int AUDIT = 10;

    /**
     * 指标包裹过滤器：紧邻审计之内，同样以 {@code doFinally} 统计<b>全链路</b>结果（含早期失败），
     * 使请求量/时延/Token 指标与审计口径一致。
     */
    public static final int METRICS = 50;

    /** 鉴权：解析调用人身份并写入上下文。 */
    public static final int AUTH = 100;
    /** 限流与配额（令牌桶 + 每日 Token 配额）。 */
    public static final int RATE_LIMIT = 200;
    /** 模型解析：请求 model → 逻辑模型 + 能力。 */
    public static final int MODEL_RESOLVE = 300;
    /** 输入安全围栏。 */
    public static final int INPUT_GUARDRAIL = 400;
    /** 输入内容审计。 */
    public static final int INPUT_MODERATION = 450;
    /** 路由：负载均衡选择健康通道。 */
    public static final int ROUTE = 500;
    /** 调用：适配器翻译、请求下游、故障转移。 */
    public static final int INVOKE = 600;
    /** 输出内容审计。 */
    public static final int OUTPUT_MODERATION = 700;
    /** 输出安全围栏。 */
    public static final int OUTPUT_GUARDRAIL = 750;
}
