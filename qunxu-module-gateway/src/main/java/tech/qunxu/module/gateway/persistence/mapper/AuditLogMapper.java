package tech.qunxu.module.gateway.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import tech.qunxu.module.gateway.persistence.entity.AuditLogEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 调用审计 Mapper。批量写入与用量聚合查询在审计服务层实现（M5）。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {
}
