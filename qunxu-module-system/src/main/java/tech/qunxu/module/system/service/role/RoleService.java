package tech.qunxu.module.system.service.role;

import com.baomidou.dynamic.datasource.annotation.DS;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tech.qunxu.framework.common.exception.util.ServiceExceptionUtil;
import tech.qunxu.framework.common.pojo.PageResult;
import tech.qunxu.framework.common.util.collection.CollectionUtils;
import tech.qunxu.module.system.controller.admin.role.vo.RolePageReqVO;
import tech.qunxu.module.system.dal.dataobject.role.RoleDO;
import tech.qunxu.module.system.dal.mysql.role.RoleMapper;
import tech.qunxu.module.system.enums.ErrorCodeConstants;
import tech.qunxu.module.system.enums.RoleTypeEnum;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 角色服务。
 */
@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleMapper roleMapper;

    /**
     * 分页查询角色
     */
    @DS("slave")
    public PageResult<RoleDO> getRolePage(RolePageReqVO reqVO) {
        return roleMapper.selectPage(reqVO, new LambdaQueryWrapper<RoleDO>()
                .like(StrUtil.isNotEmpty(reqVO.getName()), RoleDO::getName, reqVO.getName())
                .like(StrUtil.isNotEmpty(reqVO.getCode()), RoleDO::getCode, reqVO.getCode())
                .eq(reqVO.getStatus() != null, RoleDO::getStatus, reqVO.getStatus())
                .orderByAsc(RoleDO::getSort));
    }

    /**
     * 获取角色
     */
    @DS("slave")
    public RoleDO getRole(Long id) {
        return roleMapper.selectById(id);
    }

    /**
     * 获取全部角色（用于下拉框）
     */
    @DS("slave")
    public List<RoleDO> getRoleSimpleList() {
        return roleMapper.selectList(new LambdaQueryWrapper<RoleDO>().orderByAsc(RoleDO::getSort));
    }

    /**
     * 根据角色编号集合获取角色列表
     */
    @DS("slave")
    public List<RoleDO> getRoleListByRoleIds(Collection<Long> roleIds) {
        if (CollectionUtils.isEmpty(roleIds)) {
            return Collections.emptyList();
        }
        return roleMapper.selectList(new LambdaQueryWrapper<RoleDO>().in(RoleDO::getId, roleIds));
    }

    /**
     * 创建角色
     */
    public Long createRole(RoleDO role) {
        validateRole(role);
        role.setType(RoleTypeEnum.CUSTOM.getType());
        roleMapper.insert(role);
        return role.getId();
    }

    /**
     * 更新角色
     */
    public void updateRole(RoleDO role) {
        validateRoleExists(role.getId());
        validateSystemRole(role.getId(), "修改");
        validateRole(role);
        roleMapper.updateById(role);
    }

    /**
     * 删除角色
     */
    public void deleteRole(Long id) {
        validateRoleExists(id);
        validateSystemRole(id, "删除");
        roleMapper.deleteById(id);
    }

    private void validateRole(RoleDO role) {
        // 角色编码唯一
        Long count = roleMapper.selectCount(new LambdaQueryWrapper<RoleDO>()
                .eq(RoleDO::getCode, role.getCode())
                .ne(role.getId() != null, RoleDO::getId, role.getId()));
        if (count > 0) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.ROLE_CODE_EXISTS);
        }
        // 角色名唯一
        count = roleMapper.selectCount(new LambdaQueryWrapper<RoleDO>()
                .eq(RoleDO::getName, role.getName())
                .ne(role.getId() != null, RoleDO::getId, role.getId()));
        if (count > 0) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.ROLE_NAME_EXISTS);
        }
    }

    private void validateRoleExists(Long id) {
        if (roleMapper.selectById(id) == null) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.ROLE_NOT_EXISTS);
        }
    }

    private void validateSystemRole(Long id, String operation) {
        RoleDO role = roleMapper.selectById(id);
        if (role != null && RoleTypeEnum.SYSTEM.getType().equals(role.getType())) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.ROLE_SYSTEM_CANNOT_DELETE);
        }
    }

}
