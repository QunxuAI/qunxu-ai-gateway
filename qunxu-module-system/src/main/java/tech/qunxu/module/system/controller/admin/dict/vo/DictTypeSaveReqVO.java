package tech.qunxu.module.system.controller.admin.dict.vo;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * 字典类型新增 / 修改请求。
 */
@Data
public class DictTypeSaveReqVO {

    /** 字典类型编号（修改时必填） */
    private Long id;

    /** 字典名称 */
    @NotEmpty(message = "字典名称不能为空")
    private String name;

    /** 字典类型 */
    @NotEmpty(message = "字典类型不能为空")
    private String type;

    /** 状态（0 关闭 1 开启） */
    private Integer status;

    /** 备注 */
    private String remark;

}
