package tech.qunxu.module.system.dal.dataobject.notice;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tech.qunxu.framework.tenant.core.db.TenantBaseDO;

/**
 * 通知公告 DO。
 */
@TableName("system_notice")
@Data
@EqualsAndHashCode(callSuper = true)
public class NoticeDO extends TenantBaseDO {

    /** 标题 */
    private String title;

    /** 内容 */
    private String content;

    /** 类型：1 通知，2 公告 */
    private Integer type;

    /** 状态：0 关闭，1 开启 */
    private Integer status;

}
