package tech.qunxu.module.system.controller.admin.role.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import tech.qunxu.framework.common.pojo.PageParam;

/**
 * 角色分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RolePageReqVO extends PageParam {

    /** 角色名称 */
    private String name;

    /** 角色编码 */
    private String code;

    /** 状态（0 关闭 1 开启） */
    private Integer status;

}
