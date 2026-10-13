package tech.qunxu.module.system.service.auth;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tech.qunxu.framework.common.enums.CommonStatusEnum;
import tech.qunxu.framework.common.enums.UserTypeEnum;
import tech.qunxu.framework.common.exception.util.ServiceExceptionUtil;
import tech.qunxu.framework.common.util.json.JsonUtils;
import tech.qunxu.framework.common.util.security.SecurityUtils;
import tech.qunxu.framework.common.util.servlet.ServletUtils;
import tech.qunxu.framework.security.core.LoginUser;
import tech.qunxu.module.system.controller.admin.auth.vo.AuthLoginReqVO;
import tech.qunxu.module.system.controller.admin.auth.vo.AuthLoginRespVO;
import tech.qunxu.module.system.controller.admin.auth.vo.AuthPermissionInfoRespVO;
import tech.qunxu.module.system.controller.admin.auth.vo.AuthRefreshTokenReqVO;
import tech.qunxu.module.system.dal.dataobject.oauth2.OAuth2ClientDO;
import tech.qunxu.module.system.dal.dataobject.user.UserDO;
import tech.qunxu.module.system.enums.ErrorCodeConstants;
import tech.qunxu.module.system.enums.LogTypeEnum;
import tech.qunxu.module.system.service.captcha.CaptchaService;
import tech.qunxu.module.system.service.logger.LoginLogService;
import tech.qunxu.module.system.service.oauth2.OAuth2ClientService;
import tech.qunxu.module.system.service.oauth2.OAuth2Token;
import tech.qunxu.module.system.service.oauth2.OAuth2TokenService;
import tech.qunxu.module.system.service.permission.PermissionService;
import tech.qunxu.framework.tenant.core.context.TenantContextHolder;
import tech.qunxu.module.system.service.tenant.TenantService;
import tech.qunxu.module.system.service.user.UserService;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 认证服务：登录、登出、刷新令牌、获取用户权限信息。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 默认租户编号：请求头未携带 tenant-id 时使用 */
    private static final Long DEFAULT_TENANT_ID = 1L;

    private final UserService userService;
    private final PermissionService permissionService;
    private final OAuth2TokenService oauth2TokenService;
    private final OAuth2ClientService oauth2ClientService;
    private final CaptchaService captchaService;
    private final TenantService tenantService;
    private final LoginLogService loginLogService;

    /**
     * 登录
     */
    public AuthLoginRespVO login(AuthLoginReqVO reqVO, HttpServletRequest request) {
        String userIp = ServletUtils.getClientIP(request);
        String userAgent = request.getHeader("User-Agent");
        // 1. 校验验证码
        captchaService.validCaptcha(reqVO.getCaptchaUuid(), reqVO.getCaptchaCode());
        // 2. 校验客户端
        OAuth2ClientDO client = oauth2ClientService.getOAuth2ClientByClientId(reqVO.getClientId());
        oauth2ClientService.validClientSecret(reqVO.getClientSecret(), client);
        // 3. 校验租户
        Long tenantId = getTenantId();
        tenantService.validTenant(tenantId);
        // 4. 校验用户与密码
        UserDO user = userService.getUserByUsernameAndTenant(reqVO.getUsername(), tenantId);
        if (user == null || !SecurityUtils.matchesPassword(reqVO.getPassword(), user.getPassword(), user.getSalt())) {
            loginLogService.createLoginLog(LogTypeEnum.LOGIN, null, UserTypeEnum.ADMIN.getValue(),
                    reqVO.getUsername(), 0, userIp, userAgent);
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.AUTH_LOGIN_BAD_CREDENTIALS);
        }
        if (CommonStatusEnum.isDisable(user.getStatus())) {
            loginLogService.createLoginLog(LogTypeEnum.LOGIN, user.getId(), UserTypeEnum.ADMIN.getValue(),
                    user.getUsername(), 0, userIp, userAgent);
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.USER_STATUS_DISABLE);
        }
        // 5. 构建登录用户并创建令牌
        LoginUser loginUser = buildLoginUser(user, tenantId);
        OAuth2Token token = oauth2TokenService.createAccessToken(loginUser, client);
        loginLogService.createLoginLog(LogTypeEnum.LOGIN, user.getId(), UserTypeEnum.ADMIN.getValue(),
                user.getUsername(), 1, userIp, userAgent);
        return new AuthLoginRespVO(user.getId(), token.getAccessToken(),
                token.getRefreshToken(), token.getExpiresTime());
    }

    /**
     * 登出
     */
    public void logout(String accessToken, HttpServletRequest request) {
        if (StrUtil.isNotEmpty(accessToken)) {
            oauth2TokenService.removeAccessToken(accessToken);
            // 记录登出日志
            LoginUser loginUser = tech.qunxu.framework.security.core.SecurityUtils.getLoginUser().orElse(null);
            if (loginUser != null) {
                loginLogService.createLoginLog(LogTypeEnum.LOGOUT, loginUser.getId(), loginUser.getUserType(),
                        loginUser.getUsername(), 1, ServletUtils.getClientIP(request), request.getHeader("User-Agent"));
            }
        }
    }

    /**
     * 刷新令牌
     */
    public AuthLoginRespVO refreshToken(AuthRefreshTokenReqVO reqVO) {
        OAuth2ClientDO client = oauth2ClientService.getOAuth2ClientByClientId(reqVO.getClientId());
        OAuth2Token token = oauth2TokenService.refreshAccessToken(reqVO.getRefreshToken(), client);
        return new AuthLoginRespVO(null, token.getAccessToken(),
                token.getRefreshToken(), token.getExpiresTime());
    }

    /**
     * 获取登录用户权限信息（角色、权限，实时查询数据库）
     */
    public AuthPermissionInfoRespVO getPermissionInfo() {
        Long userId = tech.qunxu.framework.security.core.SecurityUtils.getLoginUserId();
        Set<String> roles = permissionService.getRoleCodesByUserId(userId);
        Set<String> permissions = permissionService.getMenuPermissionListByUserId(userId);
        return new AuthPermissionInfoRespVO(UserTypeEnum.ADMIN.getValue(), roles, permissions);
    }

    /**
     * 构建登录用户
     */
    private LoginUser buildLoginUser(UserDO user, Long tenantId) {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(user.getId());
        loginUser.setUserType(UserTypeEnum.ADMIN.getValue());
        loginUser.setTenantId(tenantId);
        loginUser.setDeptId(user.getDeptId());
        loginUser.setPostIds(parseLongSet(user.getPostIds()));
        Set<Long> roleIds = permissionService.getRoleIdsByUserId(user.getId());
        loginUser.setRoleIds(roleIds);
        loginUser.setRoleCodes(permissionService.getRoleCodesByUserId(user.getId()));
        loginUser.setPermissions(permissionService.getMenuPermissionListByUserId(user.getId()));
        loginUser.setUsername(user.getUsername());
        loginUser.setNickname(user.getNickname());
        loginUser.setAvatar(user.getAvatar());
        loginUser.setEmail(user.getEmail());
        loginUser.setMobile(user.getMobile());
        return loginUser;
    }

    /**
     * 解析 JSON 字符串为 Long 集合
     */
    private Set<Long> parseLongSet(String json) {
        if (StrUtil.isEmpty(json)) {
            return new HashSet<>();
        }
        List<Long> list = JsonUtils.parseArray(json, Long.class);
        return new HashSet<>(list);
    }

    private Long getTenantId() {
        Long tenantId = TenantContextHolder.getTenantId();
        return tenantId != null ? tenantId : DEFAULT_TENANT_ID;
    }

}
