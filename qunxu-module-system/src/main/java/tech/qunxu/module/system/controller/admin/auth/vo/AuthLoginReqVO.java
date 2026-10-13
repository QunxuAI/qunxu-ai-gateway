package tech.qunxu.module.system.controller.admin.auth.vo;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * 登录请求。
 */
@Data
public class AuthLoginReqVO {

    @NotEmpty(message = "账号不能为空")
    private String username;

    @NotEmpty(message = "密码不能为空")
    private String password;

    /** 验证码 UUID */
    private String captchaUuid;

    /** 验证码答案 */
    private String captchaCode;

    /** 客户端编号 */
    private String clientId;

    /** 客户端密钥 */
    private String clientSecret;

}
