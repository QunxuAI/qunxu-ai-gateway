package tech.qunxu.module.system.dal.mysql.oauth2;

import org.apache.ibatis.annotations.Mapper;

import tech.qunxu.framework.mybatis.core.mapper.BaseMapperX;
import tech.qunxu.module.system.dal.dataobject.oauth2.OAuth2ClientDO;

@Mapper
public interface OAuth2ClientMapper extends BaseMapperX<OAuth2ClientDO> {
}
