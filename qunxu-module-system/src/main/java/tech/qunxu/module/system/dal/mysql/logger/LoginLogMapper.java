package tech.qunxu.module.system.dal.mysql.logger;

import org.apache.ibatis.annotations.Mapper;

import tech.qunxu.framework.mybatis.core.mapper.BaseMapperX;
import tech.qunxu.module.system.dal.dataobject.logger.LoginLogDO;

@Mapper
public interface LoginLogMapper extends BaseMapperX<LoginLogDO> {
}
