package tech.qunxu.module.system.dal.dataobject.dict;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tech.qunxu.framework.tenant.core.db.TenantBaseDO;

/**
 * 字典数据 DO。
 */
@TableName("system_dict_data")
@Data
@EqualsAndHashCode(callSuper = true)
public class DictDataDO extends TenantBaseDO {

    /** 显示顺序 */
    private Integer sort;

    /** 字典标签 */
    private String label;

    /** 字典值 */
    private String value;

    /** 字典类型 */
    private String dictType;

    /** 状态：0 关闭，1 开启 */
    private Integer status;

    /** 颜色类型：default、primary、success、info、warning、danger */
    private String colorType;

    /** CSS 样式 */
    private String cssClass;

    /** 备注 */
    private String remark;

}
