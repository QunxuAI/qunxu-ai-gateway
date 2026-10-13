package tech.qunxu.module.gateway.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import tech.qunxu.module.gateway.persistence.entity.SensitiveWordEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 敏感词 Mapper。
 */
@Mapper
public interface SensitiveWordMapper extends BaseMapper<SensitiveWordEntity> {
}
