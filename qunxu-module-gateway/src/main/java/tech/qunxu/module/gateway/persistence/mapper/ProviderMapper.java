package tech.qunxu.module.gateway.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 供应商 Mapper。继承 MyBatis-Plus {@link BaseMapper} 获得单表 CRUD，
 * 复杂查询由服务层用 LambdaQueryWrapper 组装。
 */
@Mapper
public interface ProviderMapper extends BaseMapper<ProviderEntity> {
}
