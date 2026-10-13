package tech.qunxu.module.system.controller.admin.role;

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
import tech.qunxu.framework.common.pojo.PageResult;
import tech.qunxu.framework.common.util.collection.CollectionUtils;
import tech.qunxu.framework.common.util.json.JsonUtils;
import tech.qunxu.module.system.controller.admin.role.vo.RoleAssignMenuReqVO;
import tech.qunxu.module.system.controller.admin.role.vo.RolePageReqVO;
import tech.qunxu.module.system.controller.admin.role.vo.RoleRespVO;
import tech.qunxu.module.system.controller.admin.role.vo.RoleSaveReqVO;
import tech.qunxu.module.system.dal.dataobject.role.RoleDO;
import tech.qunxu.module.system.framework.operatelog.core.annotation.OperateLog;
import tech.qunxu.module.system.service.permission.PermissionService;
import tech.qunxu.module.system.service.role.RoleService;

import java.util.List;
import java.util.Set;

/**
 * 管理后台 - 角色接口。
 */
@Tag(name = "管理后台 - 角色")
@RestController
@RequestMapping("/admin-api/system/role")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;
    private final PermissionService permissionService;

    @GetMapping("/page")
    @Operation(summary = "分页查询角色")
    @PreAuthorize("@ss.hasPermission('system:role:query')")
    public CommonResult<PageResult<RoleRespVO>> getRolePage(RolePageReqVO reqVO) {
        PageResult<RoleDO> pageResult = roleService.getRolePage(reqVO);
        List<RoleRespVO> vos = CollectionUtils.convertList(pageResult.getList(), RoleRespVO::from);
        return CommonResult.success(new PageResult<>(vos, pageResult.getTotal()));
    }

    @GetMapping("/get")
    @Operation(summary = "获取角色")
    @PreAuthorize("@ss.hasPermission('system:role:query')")
    public CommonResult<RoleRespVO> getRole(@RequestParam("id") Long id) {
        return CommonResult.success(RoleRespVO.from(roleService.getRole(id)));
    }

    @GetMapping("/simple-list")
    @Operation(summary = "获取全部角色（下拉框）")
    @PreAuthorize("@ss.hasPermission('system:role:query')")
    public CommonResult<List<RoleRespVO>> getSimpleRoleList() {
        List<RoleRespVO> vos = CollectionUtils.convertList(roleService.getRoleSimpleList(), RoleRespVO::from);
        return CommonResult.success(vos);
    }

    @PostMapping("/create")
    @Operation(summary = "创建角色")
    @PreAuthorize("@ss.hasPermission('system:role:create')")
    @OperateLog(module = "角色管理", name = "创建角色", type = "create")
    public CommonResult<Long> createRole(@Valid @RequestBody RoleSaveReqVO reqVO) {
        RoleDO role = buildRoleDO(reqVO);
        return CommonResult.success(roleService.createRole(role));
    }

    @PutMapping("/update")
    @Operation(summary = "更新角色")
    @PreAuthorize("@ss.hasPermission('system:role:update')")
    @OperateLog(module = "角色管理", name = "更新角色", type = "update")
    public CommonResult<Boolean> updateRole(@Valid @RequestBody RoleSaveReqVO reqVO) {
        RoleDO role = buildRoleDO(reqVO);
        role.setId(reqVO.getId());
        roleService.updateRole(role);
        return CommonResult.success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除角色")
    @PreAuthorize("@ss.hasPermission('system:role:delete')")
    @OperateLog(module = "角色管理", name = "删除角色", type = "delete")
    public CommonResult<Boolean> deleteRole(@RequestParam("id") Long id) {
        roleService.deleteRole(id);
        return CommonResult.success(true);
    }

    @GetMapping("/get-menu-list")
    @Operation(summary = "获取角色拥有的菜单编号集合")
    @PreAuthorize("@ss.hasPermission('system:role:query')")
    public CommonResult<Set<Long>> getRoleMenuList(@RequestParam("roleId") Long roleId) {
        return CommonResult.success(permissionService.getMenuIdsByRoleId(roleId));
    }

    @PutMapping("/assign-menu")
    @Operation(summary = "分配角色菜单")
    @PreAuthorize("@ss.hasPermission('system:role:assign-menu')")
    @OperateLog(module = "角色管理", name = "分配角色菜单", type = "update")
    public CommonResult<Boolean> assignMenu(@Valid @RequestBody RoleAssignMenuReqVO reqVO) {
        permissionService.assignRoleMenu(reqVO.getRoleId(), reqVO.getMenuIds());
        return CommonResult.success(true);
    }

    private RoleDO buildRoleDO(RoleSaveReqVO reqVO) {
        RoleDO role = new RoleDO();
        role.setName(reqVO.getName());
        role.setCode(reqVO.getCode());
        role.setSort(reqVO.getSort());
        role.setStatus(reqVO.getStatus());
        role.setDataScope(reqVO.getDataScope() != null ? reqVO.getDataScope() : 1);
        role.setDataScopeDeptIds(JsonUtils.toJsonString(reqVO.getDataScopeDeptIds()));
        role.setRemark(reqVO.getRemark());
        return role;
    }

}
