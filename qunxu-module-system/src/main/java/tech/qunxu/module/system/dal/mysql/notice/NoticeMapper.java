package tech.qunxu.module.system.dal.mysql.notice;

import org.apache.ibatis.annotations.Mapper;

import tech.qunxu.framework.mybatis.core.mapper.BaseMapperX;
import tech.qunxu.module.system.dal.dataobject.notice.NoticeDO;

@Mapper
public interface NoticeMapper extends BaseMapperX<NoticeDO> {
}
