package tech.qunxu.module.system.dal.dataobject.menu;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tech.qunxu.framework.tenant.core.db.TenantBaseDO;

/**
 * 菜单 DO。
 */
@TableName("system_menu")
@Data
@EqualsAndHashCode(callSuper = true)
public class MenuDO extends TenantBaseDO {

    /** 菜单名称 */
    private String name;

    /** 权限标识：菜单与按钮填写，目录可为空 */
    private String permission;

    /** 菜单类型：1 目录，2 菜单，3 按钮 */
    private Integer type;

    /** 显示顺序 */
    private Integer sort;

    /** 父菜单编号，根节点为 0 */
    private Long parentId;

    /** 路由地址 */
    private String path;

    /** 菜单图标 */
    private String icon;

    /** 组件路径 */
    private String component;

    /** 组件名称 */
    private String componentName;

    /** 状态：0 关闭，1 开启 */
    private Integer status;

    /** 是否可见：0 隐藏，1 显示 */
    private Boolean visible;

    /** 是否缓存：0 否，1 是 */
    private Boolean keepAlive;

    /** 是否总是显示：0 否，1 是 */
    private Boolean alwaysShow;

}
