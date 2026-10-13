package tech.qunxu.module.system.controller.admin.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.qunxu.framework.common.pojo.CommonResult;
import tech.qunxu.framework.common.util.collection.CollectionUtils;
import tech.qunxu.framework.security.core.SecurityUtils;
import tech.qunxu.framework.security.core.filter.TokenAuthenticationFilter;
import tech.qunxu.module.system.controller.admin.auth.vo.AuthLoginReqVO;
import tech.qunxu.module.system.controller.admin.auth.vo.AuthLoginRespVO;
import tech.qunxu.module.system.controller.admin.auth.vo.AuthPermissionInfoRespVO;
import tech.qunxu.module.system.controller.admin.auth.vo.AuthRefreshTokenReqVO;
import tech.qunxu.module.system.controller.admin.menu.vo.MenuRespVO;
import tech.qunxu.module.system.dal.dataobject.menu.MenuDO;
import tech.qunxu.module.system.framework.util.TreeUtils;
import tech.qunxu.module.system.service.auth.AuthService;
import tech.qunxu.module.system.service.captcha.CaptchaService;
import tech.qunxu.module.system.service.permission.PermissionService;

import java.util.List;

/**
 * 管理后台 - 认证接口。
 */
@Tag(name = "管理后台 - 认证")
@RestController
@RequestMapping("/admin-api/system/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final CaptchaService captchaService;
    private final PermissionService permissionService;

    @PostMapping("/login")
    @Operation(summary = "账号密码登录")
    public CommonResult<AuthLoginRespVO> login(@Valid @RequestBody AuthLoginReqVO reqVO,
                                               HttpServletRequest request) {
        return CommonResult.success(authService.login(reqVO, request));
    }

    @PostMapping("/logout")
    @Operation(summary = "登出")
    public CommonResult<Boolean> logout(HttpServletRequest request) {
        // 从请求头解析访问令牌
        String authorization = request.getHeader(TokenAuthenticationFilter.HEADER_AUTHORIZATION);
        String token = authorization != null && authorization.startsWith(TokenAuthenticationFilter.BEARER_PREFIX)
                ? authorization.substring(TokenAuthenticationFilter.BEARER_PREFIX.length()) : null;
        authService.logout(token, request);
        return CommonResult.success(true);
    }

    @PostMapping("/refresh-token")
    @Operation(summary = "刷新令牌")
    public CommonResult<AuthLoginRespVO> refreshToken(@Valid @RequestBody AuthRefreshTokenReqVO reqVO) {
        return CommonResult.success(authService.refreshToken(reqVO));
    }

    @GetMapping("/get-captcha")
    @Operation(summary = "获取验证码")
    public CommonResult<CaptchaService.CaptchaRespDTO> getCaptcha() {
        return CommonResult.success(captchaService.generateCaptcha());
    }

    @GetMapping("/get-permission-info")
    @Operation(summary = "获取登录用户的角色与权限")
    public CommonResult<AuthPermissionInfoRespVO> getPermissionInfo() {
        return CommonResult.success(authService.getPermissionInfo());
    }

    @GetMapping("/get-menus")
    @Operation(summary = "获取登录用户的菜单（树形结构）")
    public CommonResult<List<MenuRespVO>> getMenus() {
        Long userId = SecurityUtils.getLoginUserId();
        List<MenuDO> menus = permissionService.getMenuListByUserId(userId);
        List<MenuRespVO> vos = CollectionUtils.convertList(menus, MenuRespVO::from);
        return CommonResult.success(TreeUtils.build(vos, MenuRespVO::getId, MenuRespVO::getParentId,
                MenuRespVO::getChildren, MenuRespVO::setChildren));
    }

}
