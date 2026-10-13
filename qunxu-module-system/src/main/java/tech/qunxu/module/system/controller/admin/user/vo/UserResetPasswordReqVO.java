package tech.qunxu.module.system.controller.admin.user.vo;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 管理员重置密码请求。
 */
@Data
public class UserResetPasswordReqVO {

    @NotNull(message = "用户编号不能为空")
    private Long id;

    @NotEmpty(message = "密码不能为空")
    private String password;

}
