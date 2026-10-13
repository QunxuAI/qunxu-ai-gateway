package tech.qunxu.module.system.controller.admin.dept.vo;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 部门新增 / 修改请求。
 */
@Data
public class DeptSaveReqVO {

    /** 部门编号（修改时必填） */
    private Long id;

    /** 部门名称 */
    @NotEmpty(message = "部门名称不能为空")
    private String name;

    /** 父部门编号 */
    private Long parentId;

    /** 显示顺序 */
    private Integer sort;

    /** 负责人用户编号 */
    private Long leaderUserId;

    /** 联系电话 */
    private String phone;

    /** 邮箱 */
    private String email;

    /** 状态（0 关闭 1 开启） */
    @NotNull(message = "状态不能为空")
    private Integer status;

}
