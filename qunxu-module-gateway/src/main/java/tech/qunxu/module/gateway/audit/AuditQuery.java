package tech.qunxu.module.gateway.audit;

import java.time.LocalDateTime;

/**
 * 审计查询条件（多维过滤 + 分页）。所有字段可为 {@code null} 表示不过滤；分页参数经
 * {@link #normalizedPage()}/{@link #normalizedSize()} 归一化并施加安全上限，避免超大分页拖垮 DB。
 *
 * @param callerId     调用人标识（精确匹配）
 * @param tenant       租户（精确匹配）
 * @param logicalModel 逻辑模型名（精确匹配）
 * @param protocol     上游协议 OPENAI/ANTHROPIC（大小写不敏感）
 * @param status       调用结果 SUCCESS/FAILURE/BLOCKED（大小写不敏感）
 * @param channelId    通道 ID
 * @param providerId   供应商 ID
 * @param errorCode    归一化错误码
 * @param traceId      链路追踪 ID
 * @param from         起始时间（含，{@code created_at >= from}）
 * @param to           截止时间（含，{@code created_at <= to}）
 * @param page         页码，从 1 开始
 * @param size         每页条数
 */
public record AuditQuery(
        String callerId,
        String tenant,
        String logicalModel,
        String protocol,
        String status,
        Long channelId,
        Long providerId,
        String errorCode,
        String traceId,
        LocalDateTime from,
        LocalDateTime to,
        Integer page,
        Integer size) {

    /** 默认页码。 */
    public static final int DEFAULT_PAGE = 1;
    /** 默认每页条数。 */
    public static final int DEFAULT_SIZE = 20;
    /** 每页条数硬上限，防止一次性拉取过多含大文本体的审计行。 */
    public static final int MAX_SIZE = 200;

    /** 归一化页码：{@code null} 或 &lt;1 时取 {@link #DEFAULT_PAGE}。 */
    public int normalizedPage() {
        return (page == null || page < 1) ? DEFAULT_PAGE : page;
    }

    /** 归一化每页条数：{@code null} 或 &lt;1 时取 {@link #DEFAULT_SIZE}，并封顶 {@link #MAX_SIZE}。 */
    public int normalizedSize() {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    /** 无任何过滤条件的默认查询。 */
    public static AuditQuery empty() {
        return new AuditQuery(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
