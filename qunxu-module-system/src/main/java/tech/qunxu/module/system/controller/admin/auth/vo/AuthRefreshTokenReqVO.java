package tech.qunxu.module.system.controller.admin.auth.vo;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * 刷新令牌请求。
 */
@Data
public class AuthRefreshTokenReqVO {

    @NotEmpty(message = "刷新令牌不能为空")
    private String refreshToken;

    /** 客户端编号 */
    private String clientId;

    /** 客户端密钥 */
    private String clientSecret;

}
