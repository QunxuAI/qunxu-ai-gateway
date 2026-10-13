package tech.qunxu.module.gateway.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import tech.qunxu.module.gateway.persistence.entity.HealthEventEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 通道健康事件 Mapper。
 */
@Mapper
public interface HealthEventMapper extends BaseMapper<HealthEventEntity> {
}
