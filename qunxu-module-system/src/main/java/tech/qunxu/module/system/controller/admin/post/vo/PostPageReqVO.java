package tech.qunxu.module.system.controller.admin.post.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import tech.qunxu.framework.common.pojo.PageParam;

/**
 * 岗位分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PostPageReqVO extends PageParam {

    /** 岗位名称 */
    private String name;

    /** 岗位编码 */
    private String code;

    /** 状态（0 关闭 1 开启） */
    private Integer status;

}
