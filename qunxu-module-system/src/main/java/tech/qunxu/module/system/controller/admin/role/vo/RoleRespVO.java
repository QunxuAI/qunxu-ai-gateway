package tech.qunxu.module.system.controller.admin.role.vo;

import lombok.Data;
import tech.qunxu.module.system.dal.dataobject.role.RoleDO;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 角色响应。
 */
@Data
public class RoleRespVO {

    private Long id;

    private String name;

    private String code;

    private Integer sort;

    private Integer status;

    private Integer type;

    private Integer dataScope;

    /** 数据范围-指定部门编号集合（JSON） */
    private String dataScopeDeptIds;

    private String remark;

    private LocalDateTime createTime;

    /**
     * DO → VO
     */
    public static RoleRespVO from(RoleDO role) {
        RoleRespVO vo = new RoleRespVO();
        vo.setId(role.getId());
        vo.setName(role.getName());
        vo.setCode(role.getCode());
        vo.setSort(role.getSort());
        vo.setStatus(role.getStatus());
        vo.setType(role.getType());
        vo.setDataScope(role.getDataScope());
        vo.setDataScopeDeptIds(role.getDataScopeDeptIds());
        vo.setRemark(role.getRemark());
        vo.setCreateTime(role.getCreateTime());
        return vo;
    }

}
