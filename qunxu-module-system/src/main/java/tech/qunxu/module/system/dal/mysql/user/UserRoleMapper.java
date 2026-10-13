package tech.qunxu.module.system.dal.mysql.user;

import org.apache.ibatis.annotations.Mapper;

import tech.qunxu.framework.mybatis.core.mapper.BaseMapperX;
import tech.qunxu.module.system.dal.dataobject.user.UserRoleDO;

@Mapper
public interface UserRoleMapper extends BaseMapperX<UserRoleDO> {
}
