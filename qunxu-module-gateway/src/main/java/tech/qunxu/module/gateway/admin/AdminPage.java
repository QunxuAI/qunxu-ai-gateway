package tech.qunxu.module.gateway.admin;

import java.util.List;

/**
 * 管理接口通用分页结果。
 *
 * <p>与审计专用的 {@code AuditPage} 结构一致，但泛型化以复用于供应商/通道/模型/策略/敏感词/健康事件
 * 等各类配置资源的列表查询。</p>
 *
 * @param items 当前页数据（已完成输出脱敏）
 * @param total 满足过滤条件的总行数
 * @param page  当前页码（从 1 开始）
 * @param size  每页条数
 * @param <T>   行类型
 */
public record AdminPage<T>(List<T> items, long total, int page, int size) {

    public AdminPage {
        items = items != null ? items : List.of();
    }

    /** 空结果页。 */
    public static <T> AdminPage<T> empty(int page, int size) {
        return new AdminPage<>(List.of(), 0L, page, size);
    }

    /** 总页数（向上取整），{@code size<=0} 时为 0。 */
    public int totalPages() {
        return size <= 0 ? 0 : (int) Math.ceil((double) total / size);
    }
}
