package tech.qunxu.module.system.api;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tech.qunxu.framework.security.core.LoginUser;
import tech.qunxu.framework.security.core.api.OAuth2TokenApi;
import tech.qunxu.module.system.service.oauth2.OAuth2TokenService;

/**
 * {@link OAuth2TokenApi} 实现，供安全框架使用。
 */
@Service
@RequiredArgsConstructor
public class OAuth2TokenApiImpl implements OAuth2TokenApi {

    private final OAuth2TokenService oauth2TokenService;

    @Override
    public LoginUser getUserByAccessToken(String accessToken) {
        return oauth2TokenService.getLoginUserByAccessToken(accessToken);
    }

    @Override
    public boolean removeAccessToken(String accessToken) {
        return oauth2TokenService.removeAccessToken(accessToken);
    }

    @Override
    public void removeTokensByUserId(Long userId, Integer userType) {
        oauth2TokenService.removeTokensByUserId(userId);
    }

}
