package tech.qunxu.module.system.controller.admin.menu;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.qunxu.framework.common.pojo.CommonResult;
import tech.qunxu.framework.common.util.collection.CollectionUtils;
import tech.qunxu.module.system.controller.admin.menu.vo.MenuRespVO;
import tech.qunxu.module.system.controller.admin.menu.vo.MenuSaveReqVO;
import tech.qunxu.module.system.dal.dataobject.menu.MenuDO;
import tech.qunxu.module.system.framework.operatelog.core.annotation.OperateLog;
import tech.qunxu.module.system.framework.util.TreeUtils;
import tech.qunxu.module.system.service.menu.MenuService;

import java.util.List;

/**
 * 管理后台 - 菜单接口。
 */
@Tag(name = "管理后台 - 菜单")
@RestController
@RequestMapping("/admin-api/system/menu")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    @GetMapping("/list")
    @Operation(summary = "获取菜单列表（树形结构）")
    @PreAuthorize("@ss.hasPermission('system:menu:query')")
    public CommonResult<List<MenuRespVO>> getMenuList() {
        List<MenuDO> menus = menuService.getMenuList();
        List<MenuRespVO> vos = CollectionUtils.convertList(menus, MenuRespVO::from);
        return CommonResult.success(TreeUtils.build(vos, MenuRespVO::getId, MenuRespVO::getParentId,
                MenuRespVO::getChildren, MenuRespVO::setChildren));
    }

    @GetMapping("/get")
    @Operation(summary = "获取菜单")
    @PreAuthorize("@ss.hasPermission('system:menu:query')")
    public CommonResult<MenuRespVO> getMenu(@RequestParam("id") Long id) {
        return CommonResult.success(MenuRespVO.from(menuService.getMenu(id)));
    }

    @PostMapping("/create")
    @Operation(summary = "创建菜单")
    @PreAuthorize("@ss.hasPermission('system:menu:create')")
    @OperateLog(module = "菜单管理", name = "创建菜单", type = "create")
    public CommonResult<Long> createMenu(@Valid @RequestBody MenuSaveReqVO reqVO) {
        MenuDO menu = buildMenuDO(reqVO);
        return CommonResult.success(menuService.createMenu(menu));
    }

    @PutMapping("/update")
    @Operation(summary = "更新菜单")
    @PreAuthorize("@ss.hasPermission('system:menu:update')")
    @OperateLog(module = "菜单管理", name = "更新菜单", type = "update")
    public CommonResult<Boolean> updateMenu(@Valid @RequestBody MenuSaveReqVO reqVO) {
        MenuDO menu = buildMenuDO(reqVO);
        menu.setId(reqVO.getId());
        menuService.updateMenu(menu);
        return CommonResult.success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除菜单")
    @PreAuthorize("@ss.hasPermission('system:menu:delete')")
    @OperateLog(module = "菜单管理", name = "删除菜单", type = "delete")
    public CommonResult<Boolean> deleteMenu(@RequestParam("id") Long id) {
        menuService.deleteMenu(id);
        return CommonResult.success(true);
    }

    private MenuDO buildMenuDO(MenuSaveReqVO reqVO) {
        MenuDO menu = new MenuDO();
        menu.setName(reqVO.getName());
        menu.setPermission(reqVO.getPermission());
        menu.setType(reqVO.getType());
        menu.setSort(reqVO.getSort() != null ? reqVO.getSort() : 0);
        menu.setParentId(reqVO.getParentId());
        menu.setPath(reqVO.getPath());
        menu.setIcon(reqVO.getIcon());
        menu.setComponent(reqVO.getComponent());
        menu.setComponentName(reqVO.getComponentName());
        menu.setStatus(reqVO.getStatus() != null ? reqVO.getStatus() : 1);
        menu.setVisible(reqVO.getVisible() != null ? reqVO.getVisible() : true);
        menu.setKeepAlive(reqVO.getKeepAlive() != null ? reqVO.getKeepAlive() : true);
        menu.setAlwaysShow(reqVO.getAlwaysShow() != null ? reqVO.getAlwaysShow() : true);
        return menu;
    }

}
