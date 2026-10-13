package tech.qunxu.module.system.controller.admin.post.vo;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * 岗位新增 / 修改请求。
 */
@Data
public class PostSaveReqVO {

    /** 岗位编号（修改时必填） */
    private Long id;

    /** 岗位名称 */
    @NotEmpty(message = "岗位名称不能为空")
    private String name;

    /** 岗位编码 */
    @NotEmpty(message = "岗位编码不能为空")
    private String code;

    /** 显示顺序 */
    private Integer sort;

    /** 状态（0 关闭 1 开启） */
    private Integer status;

    /** 备注 */
    private String remark;

}
