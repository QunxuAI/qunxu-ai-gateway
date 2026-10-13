package tech.qunxu.module.system.controller.admin.dict.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import tech.qunxu.framework.common.pojo.PageParam;

/**
 * 字典类型分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DictTypePageReqVO extends PageParam {

    /** 字典名称 */
    private String name;

    /** 字典类型 */
    private String type;

    /** 状态（0 关闭 1 开启） */
    private Integer status;

}
