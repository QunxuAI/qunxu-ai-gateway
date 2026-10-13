package tech.qunxu.framework.tenant.core.db;

import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tech.qunxu.framework.mybatis.core.dataobject.BaseDO;

import java.io.Serial;

/**
 * 多租户实体基类。
 * <p>
 * 需要按租户隔离的数据表，其实体类继承该类，自动具备 {@code tenant_id} 字段。
 * 数据库层租户拦截器会根据该字段自动为 SQL 追加租户条件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class TenantBaseDO extends BaseDO {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 租户编号 */
    @TableField
    private Long tenantId;

}
