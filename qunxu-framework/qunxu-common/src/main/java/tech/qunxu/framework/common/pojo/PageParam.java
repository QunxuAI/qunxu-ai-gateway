package tech.qunxu.framework.common.pojo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 分页请求参数基类。
 * <p>
 * 所有分页查询接口的请求对象继承该类，统一接收页码与每页条数。
 */
@Data
public class PageParam implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 页码，从 1 开始 */
    private static final int PAGE_NO = 1;

    /** 每页条数 */
    private static final int PAGE_SIZE = 10;

    /** 每页条数上限，防止一次查询过多数据 */
    private static final int PAGE_SIZE_MAX = 100;

    @Min(value = 1, message = "页码最小值为 1")
    private Integer pageNo = PAGE_NO;

    @Min(value = 1, message = "每页条数最小值为 1")
    @Max(value = PAGE_SIZE_MAX, message = "每页条数最大值为 {value}")
    private Integer pageSize = PAGE_SIZE;

}
