package tech.qunxu.module.system.controller.admin.dict.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import tech.qunxu.framework.common.pojo.PageParam;

/**
 * 字典数据分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DictDataPageReqVO extends PageParam {

    /** 字典标签 */
    private String label;

    /** 字典类型 */
    private String dictType;

    /** 状态（0 关闭 1 开启） */
    private Integer status;

}
