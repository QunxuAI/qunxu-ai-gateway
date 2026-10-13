package tech.qunxu.module.system.dal.mysql.menu;

import org.apache.ibatis.annotations.Mapper;

import tech.qunxu.framework.mybatis.core.mapper.BaseMapperX;
import tech.qunxu.module.system.dal.dataobject.menu.MenuDO;

@Mapper
public interface MenuMapper extends BaseMapperX<MenuDO> {
}
