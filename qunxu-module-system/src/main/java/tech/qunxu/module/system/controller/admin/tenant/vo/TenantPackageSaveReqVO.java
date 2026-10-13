package tech.qunxu.module.system.controller.admin.tenant.vo;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.Set;

/**
 * 租户套餐新增 / 修改请求。
 */
@Data
public class TenantPackageSaveReqVO {

    /** 套餐编号（修改时必填） */
    private Long id;

    /** 套餐名 */
    @NotEmpty(message = "套餐名不能为空")
    private String name;

    /** 状态（0 关闭 1 开启） */
    private Integer status;

    /** 菜单编号集合 */
    private Set<Long> menuIds;

    /** 备注 */
    private String remark;

}
