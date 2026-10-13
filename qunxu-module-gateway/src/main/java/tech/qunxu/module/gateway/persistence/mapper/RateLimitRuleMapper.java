package tech.qunxu.module.gateway.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import tech.qunxu.module.gateway.persistence.entity.RateLimitRuleEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 限流配额规则 Mapper。
 */
@Mapper
public interface RateLimitRuleMapper extends BaseMapper<RateLimitRuleEntity> {
}
