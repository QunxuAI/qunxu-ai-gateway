package tech.qunxu.module.gateway.guardrail;

/**
 * 安全围栏规则 SPI。每条规则对一段文本做一项独立校验（敏感词/PII/长度/注入/正则/话题等），
 * 返回 {@link RuleOutcome}；由 {@link GuardrailEngine} 按 {@link #order()} 升序串联并聚合。
 *
 * <p>实现类注册为 Spring Bean。规则应为纯函数（无副作用、不抛异常）：所需参数一律从
 * {@link GuardrailContext#config()} 读取，异常场景由引擎兜底跳过，避免单条规则故障阻断整链。</p>
 */
public interface GuardrailRule {

    /** 规则名（稳定标识，写入审计命中标记）。 */
    String name();

    /** 执行顺序，越小越先执行。 */
    int order();

    /**
     * 校验文本。
     *
     * @param context 校验上下文（文本、方向、作用域、配置）
     * @return 裁决结果，不得为 {@code null}
     */
    RuleOutcome evaluate(GuardrailContext context);
}
