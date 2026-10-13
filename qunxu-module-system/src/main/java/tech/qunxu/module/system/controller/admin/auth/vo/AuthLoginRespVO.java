package tech.qunxu.module.system.controller.admin.auth.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 登录响应。
 */
@Data
@AllArgsConstructor
public class AuthLoginRespVO implements Serializable {

    /** 用户编号 */
    private Long userId;

    /** 访问令牌 */
    private String accessToken;

    /** 刷新令牌 */
    private String refreshToken;

    /** 访问令牌过期时间 */
    private LocalDateTime expiresTime;

}
