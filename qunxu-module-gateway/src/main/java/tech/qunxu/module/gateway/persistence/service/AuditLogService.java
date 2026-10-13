package tech.qunxu.module.gateway.persistence.service;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import tech.qunxu.module.gateway.persistence.entity.AuditLogEntity;
import tech.qunxu.module.gateway.persistence.mapper.AuditLogMapper;
import org.springframework.stereotype.Service;

/**
 * 审计日志持久化服务。继承 MyBatis-Plus {@link ServiceImpl} 以获得 JDBC 批量插入（{@code saveBatch}）
 * 与分页/条件查询能力。所有方法均为<b>阻塞 JDBC</b>，在响应式链路中须经 {@code Schedulers.boundedElastic()} 卸载。
 */
@Service
public class AuditLogService extends ServiceImpl<AuditLogMapper, AuditLogEntity> {
}
