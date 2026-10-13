package tech.qunxu.module.system.controller.admin.user.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import tech.qunxu.framework.common.pojo.PageParam;

/**
 * 用户分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserPageReqVO extends PageParam {

    /** 账号 */
    private String username;

    /** 昵称 */
    private String nickname;

    /** 手机号 */
    private String mobile;

    /** 状态（0 关闭 1 开启） */
    private Integer status;

    /** 部门编号 */
    private Long deptId;

}
