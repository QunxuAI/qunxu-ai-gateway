package tech.qunxu.module.system.controller.admin.user.vo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 用户状态更新请求。
 */
@Data
public class UserUpdateStatusReqVO {

    @NotNull(message = "用户编号不能为空")
    private Long id;

    @NotNull(message = "状态不能为空")
    private Integer status;

}
