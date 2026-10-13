package tech.qunxu.module.system.controller.admin.dict.vo;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * 字典数据新增 / 修改请求。
 */
@Data
public class DictDataSaveReqVO {

    /** 字典数据编号（修改时必填） */
    private Long id;

    /** 显示顺序 */
    private Integer sort;

    /** 字典标签 */
    @NotEmpty(message = "字典标签不能为空")
    private String label;

    /** 字典键值 */
    @NotEmpty(message = "字典键值不能为空")
    private String value;

    /** 字典类型 */
    @NotEmpty(message = "字典类型不能为空")
    private String dictType;

    /** 状态（0 关闭 1 开启） */
    private Integer status;

    /** 颜色类型 */
    private String colorType;

    /** 样式 */
    private String cssClass;

    /** 备注 */
    private String remark;

}
