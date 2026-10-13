package tech.qunxu.module.gateway.common.model;

/**
 * Token 用量。统一各供应商的 usage 字段（prompt/completion/total）。
 */
public record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {

    public static TokenUsage of(Integer prompt, Integer completion) {
        int p = prompt == null ? 0 : prompt;
        int c = completion == null ? 0 : completion;
        return new TokenUsage(p, c, p + c);
    }

    public static TokenUsage empty() {
        return new TokenUsage(0, 0, 0);
    }

    public boolean isEmpty() {
        return (promptTokens == null || promptTokens == 0)
                && (completionTokens == null || completionTokens == 0)
                && (totalTokens == null || totalTokens == 0);
    }
}
