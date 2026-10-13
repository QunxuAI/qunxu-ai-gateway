package tech.qunxu.module.system.dal.mysql.post;

import org.apache.ibatis.annotations.Mapper;

import tech.qunxu.framework.mybatis.core.mapper.BaseMapperX;
import tech.qunxu.module.system.dal.dataobject.post.PostDO;

@Mapper
public interface PostMapper extends BaseMapperX<PostDO> {
}
