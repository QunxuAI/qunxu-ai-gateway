package tech.qunxu.module.system.controller.admin.dept.vo;

import lombok.Data;
import tech.qunxu.module.system.dal.dataobject.dept.DeptDO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 部门响应。
 */
@Data
public class DeptRespVO {

    private Long id;

    private String name;

    private Long parentId;

    private Integer sort;

    private Long leaderUserId;

    private String phone;

    private String email;

    private Integer status;

    private LocalDateTime createTime;

    /** 子部门 */
    private List<DeptRespVO> children = new ArrayList<>();

    /**
     * DO → VO
     */
    public static DeptRespVO from(DeptDO dept) {
        DeptRespVO vo = new DeptRespVO();
        vo.setId(dept.getId());
        vo.setName(dept.getName());
        vo.setParentId(dept.getParentId());
        vo.setSort(dept.getSort());
        vo.setLeaderUserId(dept.getLeaderUserId());
        vo.setPhone(dept.getPhone());
        vo.setEmail(dept.getEmail());
        vo.setStatus(dept.getStatus());
        vo.setCreateTime(dept.getCreateTime());
        return vo;
    }

}
