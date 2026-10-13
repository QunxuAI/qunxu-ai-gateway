package tech.qunxu.module.system.dal.mysql.dept;

import org.apache.ibatis.annotations.Mapper;

import tech.qunxu.framework.mybatis.core.mapper.BaseMapperX;
import tech.qunxu.module.system.dal.dataobject.dept.DeptDO;

@Mapper
public interface DeptMapper extends BaseMapperX<DeptDO> {
}
