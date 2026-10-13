package tech.qunxu.module.system.controller.admin.user.vo;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * 个人资料更新请求。
 */
@Data
public class UserProfileUpdateReqVO {

    @NotEmpty(message = "昵称不能为空")
    private String nickname;

    private String email;

    private String mobile;

    private String avatar;

}
