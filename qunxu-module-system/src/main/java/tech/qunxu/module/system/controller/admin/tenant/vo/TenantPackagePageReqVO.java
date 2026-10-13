package tech.qunxu.module.system.controller.admin.tenant.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import tech.qunxu.framework.common.pojo.PageParam;

/**
 * 租户套餐分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TenantPackagePageReqVO extends PageParam {

    /** 套餐名 */
    private String name;

    /** 状态（0 关闭 1 开启） */
    private Integer status;

}
