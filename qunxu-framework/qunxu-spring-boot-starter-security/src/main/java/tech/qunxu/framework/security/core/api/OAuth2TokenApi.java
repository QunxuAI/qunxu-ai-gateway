package tech.qunxu.framework.security.core.api;

import tech.qunxu.framework.security.core.LoginUser;

/**
 * OAuth2 Token API。
 * <p>
 * 由 system 模块实现：根据访问令牌还原登录用户、删除令牌等，
 * 供安全框架的 {@code TokenAuthenticationFilter} 调用，避免框架反向依赖业务模块。
 */
public interface OAuth2TokenApi {

    /**
     * 根据访问令牌获取登录用户，令牌无效或过期时返回 null
     */
    LoginUser getUserByAccessToken(String accessToken);

    /**
     * 删除访问令牌（登出），返回是否删除成功
     */
    boolean removeAccessToken(String accessToken);

    /**
     * 删除指定用户的全部访问令牌（权限变更时使会话失效）
     */
    void removeTokensByUserId(Long userId, Integer userType);

}
