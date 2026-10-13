package tech.qunxu.module.gateway.guardrail;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可逆脱敏收集器：把命中的原始文本登记为占位符 {@code {{TYPE_n}}}，并记录「占位符 → 原文」映射。
 *
 * <p>与 {@link Masking}（等长 {@code *} 掩码，不可逆）互补：占位符在响应返回前可被
 * {@link Redactions#restore} 本地还原，从而让模型基于占位符继续推理、用户拿回真实数据，
 * 真名实姓只留在网关内存中（借鉴 AstrLink 的「本地脱敏 + 响应还原」设计）。</p>
 *
 * <p>编号采用请求级原子计数器，跨消息/跨片段唯一，避免多段脱敏时占位符冲突。
 * 映射为并发容器，兼容流式回调跨线程；快照 {@link #snapshot()} 供过滤器存入请求上下文。</p>
 */
public final class RedactionCollector {

    private final Map<String, String> map = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();

    /**
     * 登记一个命中并返回其占位符。
     *
     * @param type     占位符类型标记（如 PHONE、EMAIL、ID_CARD），用于可读性
     * @param original 命中的原始文本（映射还原时的目标值）
     * @return 形如 {@code {{PHONE_1}}} 的占位符
     */
    public String register(String type, String original) {
        String placeholder = "{{" + type + "_" + counter.incrementAndGet() + "}}";
        map.put(placeholder, original);
        return placeholder;
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    /** 返回「占位符 → 原文」的不可变快照。 */
    public Map<String, String> snapshot() {
        return new HashMap<>(map);
    }
}
