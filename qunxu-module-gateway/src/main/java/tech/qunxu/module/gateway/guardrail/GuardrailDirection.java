package tech.qunxu.module.gateway.guardrail;

/**
 * 围栏校验方向：输入（客户端 → 下游）或输出（下游 → 客户端）。
 * 部分规则（如长度上限）按方向取不同阈值。
 */
public enum GuardrailDirection {
    INPUT,
    OUTPUT
}
