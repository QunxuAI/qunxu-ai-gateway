package tech.qunxu.module.system.controller.admin.tenant.vo;

import lombok.Data;
import tech.qunxu.module.system.dal.dataobject.tenant.TenantDO;

import java.time.LocalDateTime;

/**
 * 租户响应。
 */
@Data
public class TenantRespVO {

    private Long id;

    private String name;

    private String contactName;

    private String contactMobile;

    private String contactEmail;

    private Integer status;

    private Long packageId;

    private LocalDateTime expireTime;

    private Integer accountCount;

    private String website;

    private String remark;

    private LocalDateTime createTime;

    /**
     * DO → VO
     */
    public static TenantRespVO from(TenantDO tenant) {
        TenantRespVO vo = new TenantRespVO();
        vo.setId(tenant.getId());
        vo.setName(tenant.getName());
        vo.setContactName(tenant.getContactName());
        vo.setContactMobile(tenant.getContactMobile());
        vo.setContactEmail(tenant.getContactEmail());
        vo.setStatus(tenant.getStatus());
        vo.setPackageId(tenant.getPackageId());
        vo.setExpireTime(tenant.getExpireTime());
        vo.setAccountCount(tenant.getAccountCount());
        vo.setWebsite(tenant.getWebsite());
        vo.setRemark(tenant.getRemark());
        vo.setCreateTime(tenant.getCreateTime());
        return vo;
    }

}
