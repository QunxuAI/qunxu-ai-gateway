package tech.qunxu.module.gateway.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import tech.qunxu.module.gateway.persistence.entity.ModerationPolicyEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 内容审计策略 Mapper。
 */
@Mapper
public interface ModerationPolicyMapper extends BaseMapper<ModerationPolicyEntity> {
}
