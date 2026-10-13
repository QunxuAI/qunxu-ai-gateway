package tech.qunxu.module.gateway.guardrail;

import tech.qunxu.module.gateway.common.enums.Capability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 围栏校验上下文：承载待校验文本、方向、能力、作用域标识（租户/逻辑模型）与<b>扁平化策略配置</b>。
 *
 * <p>配置为 {@code Map<String,Object>}，键采用点分命名（如 {@code pii.enabled}、{@code regex.patterns}），
 * 由 {@link GuardrailPolicyService} 依据 {@code gateway.guardrail.*} 默认值与数据库策略 {@code rules_json} 合并而成。
 * 规则据此做类型安全的读取，无需感知配置来源。</p>
 *
 * <p>{@link #withText(String)} 返回仅替换文本的副本，供引擎在 MASK 规则间传递逐步脱敏的中间文本。</p>
 *
 * @param text         待校验文本（输入或输出聚合文本）
 * @param direction    校验方向
 * @param capability   能力（chat/embedding/...），部分规则可据此豁免
 * @param logicalModel 逻辑模型名（用于 MODEL 作用域策略匹配）
 * @param tenant       租户标识（用于 TENANT 作用域策略匹配）
 * @param config       扁平化策略配置
 * @param redactions   可逆脱敏收集器（占位符模式时由输入过滤器注入，可为 {@code null}）
 */
public record GuardrailContext(
        String text,
        GuardrailDirection direction,
        Capability capability,
        String logicalModel,
        String tenant,
        Map<String, Object> config,
        RedactionCollector redactions) {

    /** 无收集器的上下文（输出方向/常规校验用）。 */
    public GuardrailContext(String text, GuardrailDirection direction, Capability capability,
                            String logicalModel, String tenant, Map<String, Object> config) {
        this(text, direction, capability, logicalModel, tenant, config, null);
    }

    /** 返回仅替换文本的副本（方向/作用域/配置/收集器不变）。 */
    public GuardrailContext withText(String newText) {
        return new GuardrailContext(newText, direction, capability, logicalModel, tenant, config, redactions);
    }

    /**
     * 解析脱敏模式：优先规则级覆盖（如 {@code pii.maskMode}），否则取全局 {@code mask.mode}，
     * 再否则取调用方默认值。模式为 {@code placeholder}（可逆占位符）或 {@code star}（等长掩码）。
     */
    public String maskMode(String rulePrefix, String defaultMode) {
        String override = string(rulePrefix + ".maskMode", null);
        return override != null ? override : string("mask.mode", defaultMode);
    }

    public boolean bool(String key, boolean defaultValue) {
        Object v = config == null ? null : config.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.intValue() != 0;
        }
        if (v instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s.trim());
        }
        return defaultValue;
    }

    public int integer(String key, int defaultValue) {
        Object v = config == null ? null : config.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public String string(String key, String defaultValue) {
        Object v = config == null ? null : config.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : defaultValue;
    }

    /** 读取字符串列表：支持 {@link List} 或逗号分隔字符串；缺失返回空列表。 */
    public List<String> stringList(String key) {
        Object v = config == null ? null : config.get(key);
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o));
                }
            }
            return out;
        }
        if (v instanceof String s && !s.isBlank()) {
            return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
        }
        return List.of();
    }
}
