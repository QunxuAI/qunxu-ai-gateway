package tech.qunxu.module.system.dal.mysql.tenant;

import org.apache.ibatis.annotations.Mapper;

import tech.qunxu.framework.mybatis.core.mapper.BaseMapperX;
import tech.qunxu.module.system.dal.dataobject.tenant.TenantPackageDO;

@Mapper
public interface TenantPackageMapper extends BaseMapperX<TenantPackageDO> {
}
