package tech.qunxu.module.system.dal.redis.oauth2;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;
import tech.qunxu.framework.security.core.LoginUser;

import java.time.Duration;
import java.util.Set;

/**
 * OAuth2 访问令牌、刷新令牌的 Redis 存储。
 * <p>
 * Key 设计：
 * <ul>
 *     <li>{@code oauth2:access-token:{token}}：访问令牌 → 登录用户信息</li>
 *     <li>{@code oauth2:access-token:user:{userId}}：用户 → 其拥有的访问令牌集合</li>
 *     <li>{@code oauth2:refresh-token:{token}}：刷新令牌 → 关联的访问令牌</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class OAuth2TokenRedisDAO {

    private static final String ACCESS_TOKEN_KEY = "oauth2:access-token:";
    private static final String ACCESS_TOKEN_USER_KEY = "oauth2:access-token:user:";
    private static final String REFRESH_TOKEN_KEY = "oauth2:refresh-token:";

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 写入访问令牌
     */
    public void setAccessToken(String token, LoginUser loginUser, Duration timeout) {
        redisTemplate.opsForValue().set(ACCESS_TOKEN_KEY + token, loginUser, timeout);
        // 维护用户 → 令牌集合，方便权限变更时批量下线
        redisTemplate.opsForSet().add(ACCESS_TOKEN_USER_KEY + loginUser.getId(), token);
        redisTemplate.expire(ACCESS_TOKEN_USER_KEY + loginUser.getId(), timeout);
    }

    /**
     * 读取访问令牌
     */
    public LoginUser getAccessToken(String token) {
        Object value = redisTemplate.opsForValue().get(ACCESS_TOKEN_KEY + token);
        return value instanceof LoginUser loginUser ? loginUser : null;
    }

    /**
     * 删除访问令牌
     */
    public void removeAccessToken(String token) {
        LoginUser loginUser = getAccessToken(token);
        redisTemplate.delete(ACCESS_TOKEN_KEY + token);
        if (loginUser != null) {
            redisTemplate.opsForSet().remove(ACCESS_TOKEN_USER_KEY + loginUser.getId(), token);
        }
    }

    /**
     * 删除指定用户的全部访问令牌
     */
    public void removeAccessTokensByUserId(Long userId) {
        String userKey = ACCESS_TOKEN_USER_KEY + userId;
        Set<Object> tokens = redisTemplate.opsForSet().members(userKey);
        if (tokens != null) {
            for (Object token : tokens) {
                redisTemplate.delete(ACCESS_TOKEN_KEY + token);
            }
        }
        redisTemplate.delete(userKey);
    }

    /**
     * 写入刷新令牌
     */
    public void setRefreshToken(String refreshToken, String accessToken, Duration timeout) {
        redisTemplate.opsForValue().set(REFRESH_TOKEN_KEY + refreshToken, accessToken, timeout);
    }

    /**
     * 读取刷新令牌关联的访问令牌
     */
    public String getRefreshToken(String refreshToken) {
        Object value = redisTemplate.opsForValue().get(REFRESH_TOKEN_KEY + refreshToken);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 删除刷新令牌
     */
    public void removeRefreshToken(String refreshToken) {
        redisTemplate.delete(REFRESH_TOKEN_KEY + refreshToken);
    }

}
