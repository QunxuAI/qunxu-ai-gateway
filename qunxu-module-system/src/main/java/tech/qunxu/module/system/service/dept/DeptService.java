package tech.qunxu.module.system.service.dept;

import com.baomidou.dynamic.datasource.annotation.DS;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tech.qunxu.framework.common.exception.util.ServiceExceptionUtil;
import tech.qunxu.framework.common.util.collection.CollectionUtils;
import tech.qunxu.module.system.dal.dataobject.dept.DeptDO;
import tech.qunxu.module.system.dal.dataobject.user.UserDO;
import tech.qunxu.module.system.dal.mysql.dept.DeptMapper;
import tech.qunxu.module.system.dal.mysql.user.UserMapper;
import tech.qunxu.module.system.enums.ErrorCodeConstants;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 部门服务。
 */
@Service
@RequiredArgsConstructor
public class DeptService {

    private final DeptMapper deptMapper;
    private final UserMapper userMapper;

    /**
     * 获取部门
     */
    @DS("slave")
    public DeptDO getDept(Long id) {
        return deptMapper.selectById(id);
    }

    /**
     * 获取全部部门（按显示顺序排序）
     */
    @DS("slave")
    public List<DeptDO> getDeptList() {
        return deptMapper.selectList(new LambdaQueryWrapper<DeptDO>().orderByAsc(DeptDO::getSort));
    }

    /**
     * 创建部门
     */
    public Long createDept(DeptDO dept) {
        validateDept(dept);
        deptMapper.insert(dept);
        return dept.getId();
    }

    /**
     * 更新部门
     */
    public void updateDept(DeptDO dept) {
        validateDeptExists(dept.getId());
        validateDept(dept);
        deptMapper.updateById(dept);
    }

    /**
     * 删除部门
     */
    public void deleteDept(Long id) {
        validateDeptExists(id);
        // 存在子部门不允许删除
        Long childCount = deptMapper.selectCount(new LambdaQueryWrapper<DeptDO>().eq(DeptDO::getParentId, id));
        if (childCount > 0) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.DEPT_EXISTS_CHILDREN);
        }
        // 存在用户不允许删除
        Long userCount = userMapper.selectCount(new LambdaQueryWrapper<UserDO>().eq(UserDO::getDeptId, id));
        if (userCount > 0) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.DEPT_EXISTS_USER);
        }
        deptMapper.deleteById(id);
    }

    /**
     * 获取指定部门及其全部子孙部门编号
     */
    @DS("slave")
    public List<Long> getChildDeptIds(Long parentId) {
        List<DeptDO> allDepts = getDeptList();
        Set<Long> result = new HashSet<>();
        collectChildDeptIds(allDepts, parentId, result);
        return new ArrayList<>(result);
    }

    private void collectChildDeptIds(List<DeptDO> allDepts, Long parentId, Set<Long> result) {
        for (DeptDO dept : allDepts) {
            if (parentId.equals(dept.getParentId())) {
                result.add(dept.getId());
                collectChildDeptIds(allDepts, dept.getId(), result);
            }
        }
    }

    private void validateDept(DeptDO dept) {
        if (dept.getParentId() == null) {
            dept.setParentId(0L);
        }
        // 父部门存在
        if (!dept.getParentId().equals(0L)) {
            DeptDO parentDept = deptMapper.selectById(dept.getParentId());
            if (parentDept == null) {
                throw ServiceExceptionUtil.exception(ErrorCodeConstants.DEPT_NOT_EXISTS);
            }
        }
        // 不能设置自己为父部门
        if (dept.getId() != null && dept.getParentId().equals(dept.getId())) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.DEPT_PARENT_ERROR);
        }
        // 同级部门名唯一
        Long count = deptMapper.selectCount(new LambdaQueryWrapper<DeptDO>()
                .eq(DeptDO::getName, dept.getName())
                .eq(DeptDO::getParentId, dept.getParentId())
                .ne(dept.getId() != null, DeptDO::getId, dept.getId()));
        if (count > 0) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.DEPT_NAME_DUPLICATE);
        }
    }

    private void validateDeptExists(Long id) {
        if (deptMapper.selectById(id) == null) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.DEPT_NOT_EXISTS);
        }
    }

}
