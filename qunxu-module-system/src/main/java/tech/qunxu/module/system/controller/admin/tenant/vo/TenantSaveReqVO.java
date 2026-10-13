package tech.qunxu.module.system.controller.admin.tenant.vo;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户新增 / 修改请求。
 */
@Data
public class TenantSaveReqVO {

    /** 租户编号（修改时必填） */
    private Long id;

    /** 租户名 */
    @NotEmpty(message = "租户名不能为空")
    private String name;

    /** 联系人 */
    private String contactName;

    /** 联系手机 */
    private String contactMobile;

    /** 联系邮箱 */
    private String contactEmail;

    /** 状态（0 关闭 1 开启） */
    private Integer status;

    /** 套餐编号 */
    private Long packageId;

    /** 过期时间 */
    private LocalDateTime expireTime;

    /** 账号数量 */
    private Integer accountCount;

    /** 官网地址 */
    private String website;

    /** 备注 */
    private String remark;

}
