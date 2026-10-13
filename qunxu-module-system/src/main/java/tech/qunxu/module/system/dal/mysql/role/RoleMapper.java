package tech.qunxu.module.system.dal.mysql.role;

import org.apache.ibatis.annotations.Mapper;

import tech.qunxu.framework.mybatis.core.mapper.BaseMapperX;
import tech.qunxu.module.system.dal.dataobject.role.RoleDO;

@Mapper
public interface RoleMapper extends BaseMapperX<RoleDO> {
}
