package tech.qunxu.module.system.controller.admin.menu.vo;

import lombok.Data;
import tech.qunxu.module.system.dal.dataobject.menu.MenuDO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 菜单响应。
 */
@Data
public class MenuRespVO {

    private Long id;

    private String name;

    private String permission;

    private Integer type;

    private Integer sort;

    private Long parentId;

    private String path;

    private String icon;

    private String component;

    private String componentName;

    private Integer status;

    private Boolean visible;

    private Boolean keepAlive;

    private Boolean alwaysShow;

    private LocalDateTime createTime;

    /** 子菜单 */
    private List<MenuRespVO> children = new ArrayList<>();

    /**
     * DO → VO
     */
    public static MenuRespVO from(MenuDO menu) {
        MenuRespVO vo = new MenuRespVO();
        vo.setId(menu.getId());
        vo.setName(menu.getName());
        vo.setPermission(menu.getPermission());
        vo.setType(menu.getType());
        vo.setSort(menu.getSort());
        vo.setParentId(menu.getParentId());
        vo.setPath(menu.getPath());
        vo.setIcon(menu.getIcon());
        vo.setComponent(menu.getComponent());
        vo.setComponentName(menu.getComponentName());
        vo.setStatus(menu.getStatus());
        vo.setVisible(menu.getVisible());
        vo.setKeepAlive(menu.getKeepAlive());
        vo.setAlwaysShow(menu.getAlwaysShow());
        vo.setCreateTime(menu.getCreateTime());
        return vo;
    }

}
