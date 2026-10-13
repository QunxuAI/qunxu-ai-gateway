package tech.qunxu.framework.mybatis.core.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import tech.qunxu.framework.common.pojo.PageParam;
import tech.qunxu.framework.common.pojo.PageResult;

/**
 * 基础 Mapper 接口。
 * <p>
 * 在 MyBatis Plus {@link BaseMapper} 基础上，扩展分页查询等便捷能力，
 * 业务模块的 Mapper 统一继承该接口。
 *
 * @param <T> 实体类型
 */
public interface BaseMapperX<T> extends BaseMapper<T> {

    /**
     * 分页查询：根据分页参数与查询条件返回分页结果
     */
    default PageResult<T> selectPage(PageParam pageParam, Wrapper<T> queryWrapper) {
        Page<T> page = Page.of(pageParam.getPageNo(), pageParam.getPageSize());
        IPage<T> result = selectPage(page, queryWrapper);
        return new PageResult<>(result.getRecords(), result.getTotal());
    }

}
