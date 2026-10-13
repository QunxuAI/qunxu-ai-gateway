package tech.qunxu.module.system.controller.admin.user.vo;

import lombok.Data;
import tech.qunxu.module.system.dal.dataobject.user.UserDO;

import java.time.LocalDateTime;

/**
 * 用户响应。
 */
@Data
public class UserRespVO {

    private Long id;

    private String username;

    private String nickname;

    private String mobile;

    private String email;

    private String avatar;

    private Integer status;

    private Long deptId;

    /** 岗位编号集合（JSON） */
    private String postIds;

    private String remark;

    private LocalDateTime createTime;

    /**
     * DO → VO
     */
    public static UserRespVO from(UserDO user) {
        UserRespVO vo = new UserRespVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setMobile(user.getMobile());
        vo.setEmail(user.getEmail());
        vo.setAvatar(user.getAvatar());
        vo.setStatus(user.getStatus());
        vo.setDeptId(user.getDeptId());
        vo.setPostIds(user.getPostIds());
        vo.setRemark(user.getRemark());
        vo.setCreateTime(user.getCreateTime());
        return vo;
    }

}
