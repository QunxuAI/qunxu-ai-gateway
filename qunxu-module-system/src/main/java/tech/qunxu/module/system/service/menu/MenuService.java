package tech.qunxu.module.system.service.menu;

import com.baomidou.dynamic.datasource.annotation.DS;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tech.qunxu.framework.common.exception.util.ServiceExceptionUtil;
import tech.qunxu.framework.common.util.collection.CollectionUtils;
import tech.qunxu.module.system.dal.dataobject.menu.MenuDO;
import tech.qunxu.module.system.dal.dataobject.role.RoleMenuDO;
import tech.qunxu.module.system.dal.mysql.menu.MenuMapper;
import tech.qunxu.module.system.dal.mysql.role.RoleMenuMapper;
import tech.qunxu.module.system.enums.ErrorCodeConstants;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 菜单服务。
 */
@Service
@RequiredArgsConstructor
public class MenuService {

    private final MenuMapper menuMapper;
    private final RoleMenuMapper roleMenuMapper;

    /**
     * 获取菜单
     */
    @DS("slave")
    public MenuDO getMenu(Long id) {
        return menuMapper.selectById(id);
    }

    /**
     * 获取全部菜单（按显示顺序排序）
     */
    @DS("slave")
    public List<MenuDO> getMenuList() {
        return menuMapper.selectList(new LambdaQueryWrapper<MenuDO>().orderByAsc(MenuDO::getSort));
    }

    /**
     * 根据角色编号集合获取菜单列表
     */
    @DS("slave")
    public List<MenuDO> getMenuListByRoleIds(Collection<Long> roleIds) {
        if (CollectionUtils.isEmpty(roleIds)) {
            return Collections.emptyList();
        }
        List<RoleMenuDO> roleMenus = roleMenuMapper.selectList(
                new LambdaQueryWrapper<RoleMenuDO>().in(RoleMenuDO::getRoleId, roleIds));
        List<Long> menuIds = CollectionUtils.convertList(roleMenus, RoleMenuDO::getMenuId);
        return getMenuListByMenuIds(menuIds);
    }

    /**
     * 根据菜单编号集合获取菜单列表
     */
    @DS("slave")
    public List<MenuDO> getMenuListByMenuIds(Collection<Long> menuIds) {
        if (CollectionUtils.isEmpty(menuIds)) {
            return Collections.emptyList();
        }
        return menuMapper.selectList(new LambdaQueryWrapper<MenuDO>()
                .in(MenuDO::getId, menuIds).orderByAsc(MenuDO::getSort));
    }

    /**
     * 创建菜单
     */
    public Long createMenu(MenuDO menu) {
        validateMenu(menu);
        menuMapper.insert(menu);
        return menu.getId();
    }

    /**
     * 更新菜单
     */
    public void updateMenu(MenuDO menu) {
        validateMenuExists(menu.getId());
        validateMenu(menu);
        menuMapper.updateById(menu);
    }

    /**
     * 删除菜单
     */
    public void deleteMenu(Long id) {
        validateMenuExists(id);
        // 存在子菜单不允许删除
        Long childCount = menuMapper.selectCount(new LambdaQueryWrapper<MenuDO>().eq(MenuDO::getParentId, id));
        if (childCount > 0) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.MENU_EXISTS_CHILDREN);
        }
        // 删除角色-菜单关联
        roleMenuMapper.delete(new LambdaQueryWrapper<RoleMenuDO>().eq(RoleMenuDO::getMenuId, id));
        menuMapper.deleteById(id);
    }

    private void validateMenu(MenuDO menu) {
        if (menu.getParentId() == null) {
            menu.setParentId(0L);
        }
        // 父菜单存在
        if (!menu.getParentId().equals(0L)) {
            MenuDO parentMenu = menuMapper.selectById(menu.getParentId());
            if (parentMenu == null) {
                throw ServiceExceptionUtil.exception(ErrorCodeConstants.MENU_PARENT_NOT_EXISTS);
            }
        }
        // 不能设置自己为父菜单
        if (menu.getId() != null && menu.getParentId().equals(menu.getId())) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.MENU_PARENT_ERROR);
        }
        // 同级菜单名唯一
        Long count = menuMapper.selectCount(new LambdaQueryWrapper<MenuDO>()
                .eq(MenuDO::getName, menu.getName())
                .eq(MenuDO::getParentId, menu.getParentId())
                .ne(menu.getId() != null, MenuDO::getId, menu.getId()));
        if (count > 0) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.MENU_NAME_DUPLICATE);
        }
    }

    private void validateMenuExists(Long id) {
        if (menuMapper.selectById(id) == null) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.MENU_NOT_EXISTS);
        }
    }

}
