package tech.qunxu.module.system.controller.admin.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
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
import tech.qunxu.framework.security.core.LoginUser;
import tech.qunxu.framework.security.core.SecurityUtils;
import tech.qunxu.module.system.controller.admin.user.vo.UserAssignRoleReqVO;
import tech.qunxu.module.system.controller.admin.user.vo.UserPageReqVO;
import tech.qunxu.module.system.controller.admin.user.vo.UserProfileUpdatePasswordReqVO;
import tech.qunxu.module.system.controller.admin.user.vo.UserProfileUpdateReqVO;
import tech.qunxu.module.system.controller.admin.user.vo.UserResetPasswordReqVO;
import tech.qunxu.module.system.controller.admin.user.vo.UserRespVO;
import tech.qunxu.module.system.controller.admin.user.vo.UserSaveReqVO;
import tech.qunxu.module.system.controller.admin.user.vo.UserUpdateStatusReqVO;
import tech.qunxu.module.system.dal.dataobject.user.UserDO;
import tech.qunxu.module.system.framework.operatelog.core.annotation.OperateLog;
import tech.qunxu.module.system.service.permission.PermissionService;
import tech.qunxu.module.system.service.user.UserService;

import java.util.List;
import java.util.Set;

/**
 * 管理后台 - 用户接口。
 */
@Tag(name = "管理后台 - 用户")
@RestController
@RequestMapping("/admin-api/system/user")
@RequiredArgsConstructor
@Validated
public class UserController {

    private final UserService userService;
    private final PermissionService permissionService;

    @GetMapping("/page")
    @Operation(summary = "分页查询用户")
    @PreAuthorize("@ss.hasPermission('system:user:query')")
    public CommonResult<PageResult<UserRespVO>> getUserPage(UserPageReqVO reqVO) {
        PageResult<UserDO> pageResult = userService.getUserPage(reqVO);
        List<UserRespVO> vos = CollectionUtils.convertList(pageResult.getList(), UserRespVO::from);
        return CommonResult.success(new PageResult<>(vos, pageResult.getTotal()));
    }

    @GetMapping("/get")
    @Operation(summary = "获取用户")
    @PreAuthorize("@ss.hasPermission('system:user:query')")
    public CommonResult<UserRespVO> getUser(@RequestParam("id") Long id) {
        return CommonResult.success(UserRespVO.from(userService.getUser(id)));
    }

    @GetMapping("/simple-list")
    @Operation(summary = "获取启用用户列表（下拉框）")
    @PreAuthorize("@ss.hasPermission('system:user:query')")
    public CommonResult<List<UserRespVO>> getSimpleUserList() {
        List<UserRespVO> vos = CollectionUtils.convertList(userService.getSimpleUserList(), UserRespVO::from);
        return CommonResult.success(vos);
    }

    @PostMapping("/create")
    @Operation(summary = "创建用户")
    @PreAuthorize("@ss.hasPermission('system:user:create')")
    @OperateLog(module = "用户管理", name = "创建用户", type = "create")
    public CommonResult<Long> createUser(@Valid @RequestBody UserSaveReqVO reqVO) {
        return CommonResult.success(userService.createUser(reqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新用户")
    @PreAuthorize("@ss.hasPermission('system:user:update')")
    @OperateLog(module = "用户管理", name = "更新用户", type = "update")
    public CommonResult<Boolean> updateUser(@Valid @RequestBody UserSaveReqVO reqVO) {
        userService.updateUser(reqVO);
        return CommonResult.success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除用户")
    @PreAuthorize("@ss.hasPermission('system:user:delete')")
    @OperateLog(module = "用户管理", name = "删除用户", type = "delete")
    public CommonResult<Boolean> deleteUser(@RequestParam("id") Long id) {
        userService.deleteUser(id);
        return CommonResult.success(true);
    }

    @PutMapping("/update-password")
    @Operation(summary = "修改密码（管理员）")
    @PreAuthorize("@ss.hasPermission('system:user:update-password')")
    @OperateLog(module = "用户管理", name = "修改密码", type = "update")
    public CommonResult<Boolean> updatePassword(@Valid @RequestBody UserResetPasswordReqVO reqVO) {
        userService.updatePassword(reqVO.getId(), reqVO.getPassword());
        return CommonResult.success(true);
    }

    @PutMapping("/reset-password")
    @Operation(summary = "重置密码（管理员）")
    @PreAuthorize("@ss.hasPermission('system:user:reset-password')")
    @OperateLog(module = "用户管理", name = "重置密码", type = "update")
    public CommonResult<Boolean> resetPassword(@Valid @RequestBody UserResetPasswordReqVO reqVO) {
        userService.resetPassword(reqVO.getId(), reqVO.getPassword());
        return CommonResult.success(true);
    }

    @PutMapping("/update-status")
    @Operation(summary = "更新用户状态")
    @PreAuthorize("@ss.hasPermission('system:user:update')")
    @OperateLog(module = "用户管理", name = "更新用户状态", type = "update")
    public CommonResult<Boolean> updateStatus(@Valid @RequestBody UserUpdateStatusReqVO reqVO) {
        userService.updateStatus(reqVO.getId(), reqVO.getStatus());
        return CommonResult.success(true);
    }

    @PutMapping("/assign-role")
    @Operation(summary = "分配用户角色")
    @PreAuthorize("@ss.hasPermission('system:user:assign-role')")
    @OperateLog(module = "用户管理", name = "分配用户角色", type = "update")
    public CommonResult<Boolean> assignRole(@Valid @RequestBody UserAssignRoleReqVO reqVO) {
        permissionService.assignUserRole(reqVO.getUserId(), reqVO.getRoleIds());
        return CommonResult.success(true);
    }

    // ========== 个人中心 ==========

    @GetMapping("/profile")
    @Operation(summary = "获取个人资料")
    public CommonResult<UserRespVO> getProfile() {
        Long userId = SecurityUtils.getLoginUserId();
        return CommonResult.success(UserRespVO.from(userService.getUser(userId)));
    }

    @PutMapping("/profile/update")
    @Operation(summary = "更新个人资料")
    public CommonResult<Boolean> updateProfile(@Valid @RequestBody UserProfileUpdateReqVO reqVO) {
        userService.updateProfile(reqVO);
        return CommonResult.success(true);
    }

    @PutMapping("/profile/update-password")
    @Operation(summary = "修改个人密码")
    public CommonResult<Boolean> updateUserPassword(@Valid @RequestBody UserProfileUpdatePasswordReqVO reqVO) {
        userService.updateUserPassword(reqVO.getOldPassword(), reqVO.getNewPassword());
        return CommonResult.success(true);
    }

}
