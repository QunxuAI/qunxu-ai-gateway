package tech.qunxu.module.system.controller.admin.user.vo;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.Set;

/**
 * 用户新增 / 修改请求。
 */
@Data
public class UserSaveReqVO {

    /** 用户编号（修改时必填） */
    private Long id;

    /** 账号 */
    @NotEmpty(message = "账号不能为空")
    private String username;

    /** 密码（新增时必填，修改时不更新） */
    private String password;

    /** 昵称 */
    @NotEmpty(message = "昵称不能为空")
    private String nickname;

    /** 手机号 */
    private String mobile;

    /** 邮箱 */
    private String email;

    /** 头像 */
    private String avatar;

    /** 状态（0 关闭 1 开启） */
    private Integer status;

    /** 部门编号 */
    private Long deptId;

    /** 岗位编号集合 */
    private Set<Long> postIds;

    /** 角色编号集合 */
    private Set<Long> roleIds;

    /** 备注 */
    private String remark;

}
