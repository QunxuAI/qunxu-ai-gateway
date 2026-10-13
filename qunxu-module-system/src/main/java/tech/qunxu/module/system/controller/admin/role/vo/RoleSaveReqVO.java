package tech.qunxu.module.system.controller.admin.role.vo;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.Set;

/**
 * 角色新增 / 修改请求。
 */
@Data
public class RoleSaveReqVO {

    /** 角色编号（修改时必填） */
    private Long id;

    /** 角色名称 */
    @NotEmpty(message = "角色名称不能为空")
    private String name;

    /** 角色编码 */
    @NotEmpty(message = "角色编码不能为空")
    private String code;

    /** 显示顺序 */
    private Integer sort;

    /** 状态（0 关闭 1 开启） */
    private Integer status;

    /** 数据范围（1 全部 2 指定部门 3 本部门 4 本部门及以下 5 仅本人） */
    private Integer dataScope;

    /** 数据范围-指定部门编号集合 */
    private Set<Long> dataScopeDeptIds;

    /** 备注 */
    private String remark;

}
