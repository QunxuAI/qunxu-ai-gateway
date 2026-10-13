package tech.qunxu.module.gateway.audit;

import tech.qunxu.module.gateway.persistence.entity.AuditLogEntity;

import java.util.List;

/**
 * 审计分页结果。
 *
 * @param items 当前页审计行（含请求/响应体，已按写入时上限截断/脱敏）
 * @param total 满足过滤条件的总行数
 * @param page  当前页码（从 1 开始）
 * @param size  每页条数
 */
public record AuditPage(List<AuditLogEntity> items, long total, int page, int size) {

    public AuditPage {
        items = items != null ? items : List.of();
    }

    /** 空结果页。 */
    public static AuditPage empty(int page, int size) {
        return new AuditPage(List.of(), 0L, page, size);
    }

    /** 总页数（向上取整），{@code size<=0} 时为 0。 */
    public int totalPages() {
        return size <= 0 ? 0 : (int) Math.ceil((double) total / size);
    }
}
