package tech.qunxu.framework.mybatis.core.dataobject;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 数据库实体基类。
 * <p>
 * 所有数据表统一包含主键、创建人、创建时间、更新人、更新时间、逻辑删除字段。
 * 这些字段由 MyBatis Plus 自动填充或自动处理，业务代码无需关心。
 */
@Data
public abstract class BaseDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId
    private Long id;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 最后更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 创建者（用户 ID 字符串） */
    @TableField(fill = FieldFill.INSERT)
    private String creator;

    /** 更新者（用户 ID 字符串） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updater;

    /** 是否删除：0 未删除，2 已删除（与全局逻辑删除配置一致） */
    @TableLogic
    private Boolean deleted;

}
