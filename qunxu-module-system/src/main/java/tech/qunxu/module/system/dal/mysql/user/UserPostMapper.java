package tech.qunxu.module.system.dal.mysql.user;

import org.apache.ibatis.annotations.Mapper;

import tech.qunxu.framework.mybatis.core.mapper.BaseMapperX;
import tech.qunxu.module.system.dal.dataobject.user.UserPostDO;

@Mapper
public interface UserPostMapper extends BaseMapperX<UserPostDO> {
}
