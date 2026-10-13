package tech.qunxu.module.system.dal.dataobject.tenant;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tech.qunxu.framework.mybatis.core.dataobject.BaseDO;

import java.time.LocalDateTime;

/**
 * 租户 DO。
 * <p>
 * 全局表，不做租户隔离。
 */
@TableName("system_tenant")
@Data
@EqualsAndHashCode(callSuper = true)
public class TenantDO extends BaseDO {

    /** 租户名 */
    private String name;

    /** 联系人 */
    private String contactName;

    /** 联系手机号 */
    private String contactMobile;

    /** 联系邮箱 */
    private String contactEmail;

    /** 状态：0 关闭，1 开启 */
    private Integer status;

    /** 绑定租户套餐编号 */
    private Long packageId;

    /** 过期时间 */
    private LocalDateTime expireTime;

    /** 账号数量 */
    private Integer accountCount;

    /** 网站地址 */
    private String website;

    /** 备注 */
    private String remark;

}
