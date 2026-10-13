package tech.qunxu.module.system.controller.admin.menu.vo;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 菜单新增 / 修改请求。
 */
@Data
public class MenuSaveReqVO {

    /** 菜单编号（修改时必填） */
    private Long id;

    /** 菜单名称 */
    @NotEmpty(message = "菜单名称不能为空")
    private String name;

    /** 权限标识（按钮类型必填） */
    private String permission;

    /** 菜单类型（1 目录 2 菜单 3 按钮） */
    @NotNull(message = "菜单类型不能为空")
    private Integer type;

    /** 显示顺序 */
    private Integer sort;

    /** 父菜单编号 */
    private Long parentId;

    /** 路由地址（目录、菜单类型必填） */
    private String path;

    /** 图标 */
    private String icon;

    /** 组件路径（菜单类型必填） */
    private String component;

    /** 组件名 */
    private String componentName;

    /** 菜单状态（0 关闭 1 开启） */
    private Integer status;

    /** 是否可见（0 隐藏 1 显示） */
    private Boolean visible;

    /** 是否缓存（0 不缓存 1 缓存） */
    private Boolean keepAlive;

    /** 是否总是显示（0 否 1 是） */
    private Boolean alwaysShow;

}
