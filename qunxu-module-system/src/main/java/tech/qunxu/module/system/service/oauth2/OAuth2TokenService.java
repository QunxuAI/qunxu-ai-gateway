package tech.qunxu.module.system.service.oauth2;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tech.qunxu.framework.common.exception.util.ServiceExceptionUtil;
import tech.qunxu.framework.common.util.security.SecurityUtils;
import tech.qunxu.framework.security.core.LoginUser;
import tech.qunxu.module.system.dal.dataobject.oauth2.OAuth2ClientDO;
import tech.qunxu.module.system.dal.redis.oauth2.OAuth2TokenRedisDAO;
import tech.qunxu.module.system.enums.ErrorCodeConstants;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * OAuth2 令牌服务。
 */
@Service
@RequiredArgsConstructor
public class OAuth2TokenService {

    /** 默认访问令牌有效期：30 天 */
    private static final Duration DEFAULT_ACCESS_TOKEN_TIMEOUT = Duration.ofDays(30);
    /** 默认刷新令牌有效期：90 天 */
    private static final Duration DEFAULT_REFRESH_TOKEN_TIMEOUT = Duration.ofDays(90);

    private final OAuth2TokenRedisDAO tokenRedisDAO;

    /**
     * 创建访问令牌与刷新令牌
     */
    public OAuth2Token createAccessToken(LoginUser loginUser, OAuth2ClientDO client) {
        String accessToken = SecurityUtils.generateToken();
        String refreshToken = SecurityUtils.generateToken();
        Duration accessTimeout = getAccessTokenTimeout(client);
        Duration refreshTimeout = getRefreshTokenTimeout(client);
        tokenRedisDAO.setAccessToken(accessToken, loginUser, accessTimeout);
        tokenRedisDAO.setRefreshToken(refreshToken, accessToken, refreshTimeout);
        return new OAuth2Token(accessToken, refreshToken, LocalDateTime.now().plus(accessTimeout));
    }

    /**
     * 根据访问令牌获取登录用户
     */
    public LoginUser getLoginUserByAccessToken(String accessToken) {
        return tokenRedisDAO.getAccessToken(accessToken);
    }

    /**
     * 删除访问令牌（登出）
     */
    public boolean removeAccessToken(String accessToken) {
        LoginUser loginUser = tokenRedisDAO.getAccessToken(accessToken);
        tokenRedisDAO.removeAccessToken(accessToken);
        return loginUser != null;
    }

    /**
     * 删除指定用户的全部访问令牌（权限变更时下线会话）
     */
    public void removeTokensByUserId(Long userId) {
        tokenRedisDAO.removeAccessTokensByUserId(userId);
    }

    /**
     * 刷新访问令牌
     */
    public OAuth2Token refreshAccessToken(String refreshToken, OAuth2ClientDO client) {
        String oldAccessToken = tokenRedisDAO.getRefreshToken(refreshToken);
        if (oldAccessToken == null) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.AUTH_TOKEN_EXPIRED);
        }
        LoginUser loginUser = tokenRedisDAO.getAccessToken(oldAccessToken);
        if (loginUser == null) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.AUTH_TOKEN_EXPIRED);
        }
        // 删除旧令牌
        tokenRedisDAO.removeAccessToken(oldAccessToken);
        tokenRedisDAO.removeRefreshToken(refreshToken);
        // 创建新令牌
        return createAccessToken(loginUser, client);
    }

    private Duration getAccessTokenTimeout(OAuth2ClientDO client) {
        if (client.getAccessTokenValiditySeconds() != null && client.getAccessTokenValiditySeconds() > 0) {
            return Duration.ofSeconds(client.getAccessTokenValiditySeconds());
        }
        return DEFAULT_ACCESS_TOKEN_TIMEOUT;
    }

    private Duration getRefreshTokenTimeout(OAuth2ClientDO client) {
        if (client.getRefreshTokenValiditySeconds() != null && client.getRefreshTokenValiditySeconds() > 0) {
            return Duration.ofSeconds(client.getRefreshTokenValiditySeconds());
        }
        return DEFAULT_REFRESH_TOKEN_TIMEOUT;
    }

}
