package tech.qunxu.module.system.service.oauth2;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * OAuth2 令牌信息（访问令牌 + 刷新令牌）。
 */
@Data
@AllArgsConstructor
public class OAuth2Token implements Serializable {

    /** 访问令牌 */
    private String accessToken;

    /** 刷新令牌 */
    private String refreshToken;

    /** 访问令牌过期时间 */
    private LocalDateTime expiresTime;

}
