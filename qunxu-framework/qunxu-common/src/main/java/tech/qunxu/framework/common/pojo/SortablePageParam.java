package tech.qunxu.framework.common.pojo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.util.List;

/**
 * 可排序的分页请求参数。
 * <p>
 * 在 {@link PageParam} 基础上增加排序字段与排序方式。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SortablePageParam extends PageParam {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 排序字段 */
    private List<SortField> sortFields;

    /**
     * 排序字段描述
     */
    @Data
    public static class SortField {

        /** 排序字段名 */
        private String field;

        /** 排序方式：asc / desc */
        private String order;

    }

}
