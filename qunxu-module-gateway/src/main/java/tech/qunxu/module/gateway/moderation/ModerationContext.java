package tech.qunxu.module.gateway.moderation;

import tech.qunxu.module.gateway.common.enums.Capability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 内容审计上下文：承载待审文本、方向、能力、作用域标识与<b>提供方专属配置</b>（来自策略 {@code config_json}）。
 *
 * <p>与围栏上下文形态一致，便于策略服务复用同样的扁平配置约定。远程审计提供方（阿里绿网 / LLM 裁判）
 * 从 {@code config} 读取端点、凭据、模型名等参数。</p>
 *
 * @param text         待审文本
 * @param direction    方向（输入/输出，字符串形式 "INPUT"/"OUTPUT"）
 * @param capability   能力
 * @param logicalModel 逻辑模型名
 * @param tenant       租户标识
 * @param config       提供方专属扁平配置
 */
public record ModerationContext(
        String text,
        String direction,
        Capability capability,
        String logicalModel,
        String tenant,
        Map<String, Object> config) {

    public String string(String key, String defaultValue) {
        Object v = config == null ? null : config.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : defaultValue;
    }

    public String stringOrNull(String key) {
        Object v = config == null ? null : config.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
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
