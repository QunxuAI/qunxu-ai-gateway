package tech.qunxu.module.gateway.audit;

/**
 * Token 用量聚合维度。每个维度对应一个固定的 SQL 分组表达式，用于 {@code AuditQueryService#usage}。
 *
 * <p>表达式为枚举内置常量（非用户输入），直接拼入 {@code GROUP BY}/{@code SELECT} 无注入风险。</p>
 */
public enum UsageDimension {

    /** 按调用人聚合（{@code caller_id}）。 */
    CALLER("caller_id"),
    /** 按逻辑模型聚合（{@code logical_model}）。 */
    MODEL("logical_model"),
    /** 按自然日聚合（{@code DATE(created_at)}，MySQL 语法）。 */
    DAY("DATE(created_at)");

    private final String expression;

    UsageDimension(String expression) {
        this.expression = expression;
    }

    /** 分组/选择所用的 SQL 表达式。 */
    public String expression() {
        return expression;
    }

    /** 大小写不敏感解析，无匹配返回 {@code null}。 */
    public static UsageDimension of(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (UsageDimension d : values()) {
            if (d.name().equalsIgnoreCase(value.trim())) {
                return d;
            }
        }
        return null;
    }
}
