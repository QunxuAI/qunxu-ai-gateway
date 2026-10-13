package tech.qunxu.module.gateway.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import tech.qunxu.module.gateway.persistence.entity.LogicalModelEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 逻辑模型 Mapper。
 */
@Mapper
public interface LogicalModelMapper extends BaseMapper<LogicalModelEntity> {
}
