package tech.qunxu.module.gateway.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import tech.qunxu.module.gateway.persistence.entity.GuardrailPolicyEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 安全围栏策略 Mapper。
 */
@Mapper
public interface GuardrailPolicyMapper extends BaseMapper<GuardrailPolicyEntity> {
}
