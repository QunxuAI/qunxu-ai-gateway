package tech.qunxu.framework.common.pojo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 分页查询结果。
 * <p>
 * 承载分页查询返回的数据列表与总条数。
 */
@Data
public class PageResult<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 数据列表 */
    private List<T> list;

    /** 总条数 */
    private Long total;

    public PageResult() {
    }

    public PageResult(List<T> list, Long total) {
        this.list = list;
        this.total = total;
    }

    public PageResult(Long total) {
        this.list = new ArrayList<>();
        this.total = total;
    }

    /**
     * 构建空分页结果
     */
    public static <T> PageResult<T> empty() {
        return new PageResult<>(new ArrayList<>(), 0L);
    }

    /**
     * 构建空分页结果（指定总条数）
     */
    public static <T> PageResult<T> empty(Long total) {
        return new PageResult<>(new ArrayList<>(), total);
    }

}
