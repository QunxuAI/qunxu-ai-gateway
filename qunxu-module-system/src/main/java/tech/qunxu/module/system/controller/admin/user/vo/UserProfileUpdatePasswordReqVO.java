package tech.qunxu.module.system.controller.admin.user.vo;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 个人密码修改请求。
 */
@Data
public class UserProfileUpdatePasswordReqVO {

    @NotEmpty(message = "旧密码不能为空")
    private String oldPassword;

    @NotEmpty(message = "新密码不能为空")
    @Size(min = 4, max = 32, message = "新密码长度需在 4-32 位之间")
    private String newPassword;

}
