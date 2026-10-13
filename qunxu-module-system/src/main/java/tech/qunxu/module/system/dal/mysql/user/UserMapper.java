package tech.qunxu.module.system.dal.mysql.user;

import org.apache.ibatis.annotations.Mapper;

import tech.qunxu.framework.mybatis.core.mapper.BaseMapperX;
import tech.qunxu.module.system.dal.dataobject.user.UserDO;

@Mapper
public interface UserMapper extends BaseMapperX<UserDO> {
}
