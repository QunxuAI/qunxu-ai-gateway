package tech.qunxu.framework.security.core.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import tech.qunxu.framework.security.core.LoginUser;
import tech.qunxu.framework.security.core.SecurityUtils;
import tech.qunxu.framework.security.core.api.OAuth2TokenApi;

import java.io.IOException;

/**
 * Token 认证过滤器。
 * <p>
 * 从请求头 {@code Authorization: Bearer {token}} 中解析访问令牌，
 * 通过 {@link OAuth2TokenApi} 还原登录用户并写入安全上下文。
 */
@RequiredArgsConstructor
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    /** Token 请求头 */
    public static final String HEADER_AUTHORIZATION = "Authorization";

    /** Bearer 前缀 */
    public static final String BEARER_PREFIX = "Bearer ";

    private final OAuth2TokenApi oauth2TokenApi;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (StringUtils.hasText(token) && !SecurityUtils.isLogin()) {
            LoginUser loginUser = oauth2TokenApi.getUserByAccessToken(token);
            if (loginUser != null) {
                SecurityUtils.setLoginUser(loginUser);
            }
        }
        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader(HEADER_AUTHORIZATION);
        if (StringUtils.hasText(authorization) && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length());
        }
        return null;
    }

}
