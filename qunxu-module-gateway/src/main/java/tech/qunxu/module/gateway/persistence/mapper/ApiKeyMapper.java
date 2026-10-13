package tech.qunxu.module.gateway.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import tech.qunxu.module.gateway.persistence.entity.ApiKeyEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 网关 APIKey Mapper。
 */
@Mapper
public interface ApiKeyMapper extends BaseMapper<ApiKeyEntity> {
}
