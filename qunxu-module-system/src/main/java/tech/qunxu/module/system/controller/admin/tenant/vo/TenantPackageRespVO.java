package tech.qunxu.module.system.controller.admin.tenant.vo;

import lombok.Data;
import tech.qunxu.module.system.dal.dataobject.tenant.TenantPackageDO;

import java.time.LocalDateTime;

/**
 * 租户套餐响应。
 */
@Data
public class TenantPackageRespVO {

    private Long id;

    private String name;

    private Integer status;

    /** 菜单编号集合（JSON） */
    private String menuIds;

    private String remark;

    private LocalDateTime createTime;

    /**
     * DO → VO
     */
    public static TenantPackageRespVO from(TenantPackageDO tenantPackage) {
        TenantPackageRespVO vo = new TenantPackageRespVO();
        vo.setId(tenantPackage.getId());
        vo.setName(tenantPackage.getName());
        vo.setStatus(tenantPackage.getStatus());
        vo.setMenuIds(tenantPackage.getMenuIds());
        vo.setRemark(tenantPackage.getRemark());
        vo.setCreateTime(tenantPackage.getCreateTime());
        return vo;
    }

}
