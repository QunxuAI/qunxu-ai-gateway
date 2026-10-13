package tech.qunxu.module.system.service.user;

import com.baomidou.dynamic.datasource.annotation.DS;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tech.qunxu.framework.common.enums.CommonStatusEnum;
import tech.qunxu.framework.common.enums.UserTypeEnum;
import tech.qunxu.framework.common.exception.util.ServiceExceptionUtil;
import tech.qunxu.framework.common.pojo.PageResult;
import tech.qunxu.framework.common.util.json.JsonUtils;
import tech.qunxu.framework.common.util.security.SecurityUtils;
import tech.qunxu.module.system.controller.admin.user.vo.UserPageReqVO;
import tech.qunxu.module.system.controller.admin.user.vo.UserSaveReqVO;
import tech.qunxu.module.system.controller.admin.user.vo.UserProfileUpdateReqVO;
import tech.qunxu.module.system.dal.dataobject.user.UserDO;
import tech.qunxu.module.system.dal.dataobject.user.UserPostDO;
import tech.qunxu.module.system.dal.dataobject.user.UserRoleDO;
import tech.qunxu.module.system.dal.mysql.user.UserMapper;
import tech.qunxu.module.system.dal.mysql.user.UserPostMapper;
import tech.qunxu.module.system.dal.mysql.user.UserRoleMapper;
import tech.qunxu.module.system.enums.ErrorCodeConstants;
import tech.qunxu.module.system.service.oauth2.OAuth2TokenService;
import tech.qunxu.framework.datapermission.core.annotation.DataPermission;

import java.util.List;
import java.util.Set;

/**
 * 用户服务。
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserPostMapper userPostMapper;
    private final OAuth2TokenService oauth2TokenService;

    /**
     * 获取用户
     */
    @DS("slave")
    public UserDO getUser(Long id) {
        return userMapper.selectById(id);
    }

    /**
     * 根据账号与租户获取用户
     */
    @DS("slave")
    public UserDO getUserByUsernameAndTenant(String username, Long tenantId) {
        return userMapper.selectOne(new LambdaQueryWrapper<UserDO>()
                .eq(UserDO::getUsername, username)
                .eq(UserDO::getTenantId, tenantId));
    }

    /**
     * 分页查询用户
     */
    @DataPermission
    @DS("slave")
    public PageResult<UserDO> getUserPage(UserPageReqVO reqVO) {
        return userMapper.selectPage(reqVO, new LambdaQueryWrapper<UserDO>()
                .like(StrUtil.isNotEmpty(reqVO.getUsername()), UserDO::getUsername, reqVO.getUsername())
                .like(StrUtil.isNotEmpty(reqVO.getNickname()), UserDO::getNickname, reqVO.getNickname())
                .eq(StrUtil.isNotEmpty(reqVO.getMobile()), UserDO::getMobile, reqVO.getMobile())
                .eq(reqVO.getStatus() != null, UserDO::getStatus, reqVO.getStatus())
                .eq(reqVO.getDeptId() != null, UserDO::getDeptId, reqVO.getDeptId())
                .orderByDesc(UserDO::getId));
    }

    /**
     * 获取启用的用户列表（用于下拉框）
     */
    @DS("slave")
    public List<UserDO> getSimpleUserList() {
        return userMapper.selectList(new LambdaQueryWrapper<UserDO>()
                .eq(UserDO::getStatus, CommonStatusEnum.ENABLE.getStatus()));
    }

    /**
     * 创建用户
     */
    public Long createUser(UserSaveReqVO reqVO) {
        validateUsernameUnique(reqVO.getUsername(), null);
        validateMobileUnique(reqVO.getMobile(), null);
        // 加密密码
        String salt = SecurityUtils.generateSalt();
        UserDO user = new UserDO();
        user.setUsername(reqVO.getUsername());
        user.setPassword(SecurityUtils.encryptPassword(reqVO.getPassword(), salt));
        user.setSalt(salt);
        user.setNickname(reqVO.getNickname());
        user.setMobile(reqVO.getMobile());
        user.setEmail(reqVO.getEmail());
        user.setAvatar(reqVO.getAvatar());
        user.setStatus(reqVO.getStatus() != null ? reqVO.getStatus() : CommonStatusEnum.ENABLE.getStatus());
        user.setDeptId(reqVO.getDeptId());
        user.setPostIds(JsonUtils.toJsonString(reqVO.getPostIds()));
        user.setRemark(reqVO.getRemark());
        userMapper.insert(user);
        // 绑定角色、岗位
        insertUserRole(user.getId(), reqVO.getRoleIds());
        insertUserPost(user.getId(), reqVO.getPostIds());
        return user.getId();
    }

    /**
     * 更新用户
     */
    public void updateUser(UserSaveReqVO reqVO) {
        UserDO user = validateUserExists(reqVO.getId());
        validateUsernameUnique(reqVO.getUsername(), reqVO.getId());
        validateMobileUnique(reqVO.getMobile(), reqVO.getId());
        // 更新基础信息（不更新密码）
        user.setUsername(reqVO.getUsername());
        user.setNickname(reqVO.getNickname());
        user.setMobile(reqVO.getMobile());
        user.setEmail(reqVO.getEmail());
        user.setAvatar(reqVO.getAvatar());
        user.setStatus(reqVO.getStatus());
        user.setDeptId(reqVO.getDeptId());
        user.setPostIds(JsonUtils.toJsonString(reqVO.getPostIds()));
        user.setRemark(reqVO.getRemark());
        userMapper.updateById(user);
        // 重新绑定角色、岗位
        userRoleMapper.delete(new LambdaQueryWrapper<UserRoleDO>().eq(UserRoleDO::getUserId, user.getId()));
        insertUserRole(user.getId(), reqVO.getRoleIds());
        userPostMapper.delete(new LambdaQueryWrapper<UserPostDO>().eq(UserPostDO::getUserId, user.getId()));
        insertUserPost(user.getId(), reqVO.getPostIds());
        // 角色变更，下线会话
        oauth2TokenService.removeTokensByUserId(user.getId());
    }

    /**
     * 删除用户
     */
    public void deleteUser(Long id) {
        validateUserExists(id);
        // 不能删除自己
        if (id.equals(tech.qunxu.framework.security.core.SecurityUtils.getLoginUserId())) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.USER_DELETE_SELF_ERROR);
        }
        userMapper.deleteById(id);
        userRoleMapper.delete(new LambdaQueryWrapper<UserRoleDO>().eq(UserRoleDO::getUserId, id));
        userPostMapper.delete(new LambdaQueryWrapper<UserPostDO>().eq(UserPostDO::getUserId, id));
        oauth2TokenService.removeTokensByUserId(id);
    }

    /**
     * 修改密码（管理员）
     */
    public void updatePassword(Long id, String password) {
        UserDO user = validateUserExists(id);
        String salt = SecurityUtils.generateSalt();
        user.setPassword(SecurityUtils.encryptPassword(password, salt));
        user.setSalt(salt);
        userMapper.updateById(user);
        oauth2TokenService.removeTokensByUserId(id);
    }

    /**
     * 重置密码（管理员）
     */
    public void resetPassword(Long id, String password) {
        updatePassword(id, password);
    }

    /**
     * 更新用户状态
     */
    public void updateStatus(Long id, Integer status) {
        UserDO user = validateUserExists(id);
        user.setStatus(status);
        userMapper.updateById(user);
        // 禁用时下线会话
        if (CommonStatusEnum.isDisable(status)) {
            oauth2TokenService.removeTokensByUserId(id);
        }
    }

    /**
     * 统计部门下的用户数量
     */
    public Long countUserByDeptId(Long deptId) {
        return userMapper.selectCount(new LambdaQueryWrapper<UserDO>().eq(UserDO::getDeptId, deptId));
    }

    // ========== 个人中心 ==========

    /**
     * 更新个人资料
     */
    public void updateProfile(UserProfileUpdateReqVO reqVO) {
        Long userId = tech.qunxu.framework.security.core.SecurityUtils.getLoginUserId();
        UserDO user = validateUserExists(userId);
        validateMobileUnique(reqVO.getMobile(), userId);
        user.setNickname(reqVO.getNickname());
        user.setEmail(reqVO.getEmail());
        user.setMobile(reqVO.getMobile());
        user.setAvatar(reqVO.getAvatar());
        userMapper.updateById(user);
    }

    /**
     * 修改个人密码
     */
    public void updateUserPassword(String oldPassword, String newPassword) {
        Long userId = tech.qunxu.framework.security.core.SecurityUtils.getLoginUserId();
        UserDO user = validateUserExists(userId);
        // 校验旧密码
        if (!SecurityUtils.matchesPassword(oldPassword, user.getPassword(), user.getSalt())) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.USER_OLD_PASSWORD_ERROR);
        }
        // 更新密码
        String salt = SecurityUtils.generateSalt();
        user.setPassword(SecurityUtils.encryptPassword(newPassword, salt));
        user.setSalt(salt);
        userMapper.updateById(user);
        // 修改密码后下线会话
        oauth2TokenService.removeTokensByUserId(userId);
    }

    // ========== 私有方法 ==========

    private void insertUserRole(Long userId, Set<Long> roleIds) {
        if (roleIds == null) {
            return;
        }
        for (Long roleId : roleIds) {
            UserRoleDO userRole = new UserRoleDO();
            userRole.setUserId(userId);
            userRole.setRoleId(roleId);
            userRoleMapper.insert(userRole);
        }
    }

    private void insertUserPost(Long userId, Set<Long> postIds) {
        if (postIds == null) {
            return;
        }
        for (Long postId : postIds) {
            UserPostDO userPost = new UserPostDO();
            userPost.setUserId(userId);
            userPost.setPostId(postId);
            userPostMapper.insert(userPost);
        }
    }

    private void validateUsernameUnique(String username, Long excludeId) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<UserDO>()
                .eq(UserDO::getUsername, username)
                .ne(excludeId != null, UserDO::getId, excludeId));
        if (count > 0) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.USER_USERNAME_EXISTS);
        }
    }

    private void validateMobileUnique(String mobile, Long excludeId) {
        if (StrUtil.isEmpty(mobile)) {
            return;
        }
        Long count = userMapper.selectCount(new LambdaQueryWrapper<UserDO>()
                .eq(UserDO::getMobile, mobile)
                .ne(excludeId != null, UserDO::getId, excludeId));
        if (count > 0) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.USER_MOBILE_EXISTS);
        }
    }

    private UserDO validateUserExists(Long id) {
        UserDO user = userMapper.selectById(id);
        if (user == null) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.USER_NOT_EXISTS);
        }
        return user;
    }

}
