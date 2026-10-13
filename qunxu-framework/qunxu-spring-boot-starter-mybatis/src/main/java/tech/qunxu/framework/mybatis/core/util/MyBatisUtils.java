package tech.qunxu.framework.mybatis.core.util;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * MyBatis Plus 查询工具类。
 * <p>
 * 提供 {@link LambdaQueryWrapper} 的便捷扩展，简化空值判断等重复代码。
 */
public final class MyBatisUtils {

    private MyBatisUtils() {
    }

    /**
     * 向 MyBatis Plus 拦截器链的指定位置注册内置拦截器。
     * <p>
     * 业务组件（如多租户、数据权限）通过该方法将自身的内置拦截器
     * 添加到全局拦截器链中，保证与分页插件的执行顺序可控。
     * <p>
     * 注意：MyBatis Plus 3.5.17 的 {@link MybatisPlusInterceptor#getInterceptors()}
     * 返回不可变列表，需通过拷贝 + {@link MybatisPlusInterceptor#setInterceptors} 完成插入。
     */
    public static void addInterceptor(MybatisPlusInterceptor interceptor, InnerInterceptor inner, int index) {
        List<InnerInterceptor> interceptors = new ArrayList<>(interceptor.getInterceptors());
        // 防止索引越界
        interceptors.add(Math.min(index, interceptors.size()), inner);
        interceptor.setInterceptors(interceptors);
    }

    /**
     * 构建 Lambda 查询条件：当值非空时添加等值条件
     */
    public static <T> LambdaQueryWrapper<T> eqIfPresent(LambdaQueryWrapper<T> wrapper,
                                                        SFunction<T, ?> column, Object value) {
        if (value != null) {
            wrapper.eq(column, value);
        }
        return wrapper;
    }

    /**
     * 构建 Lambda 查询条件：当值非空时添加 LIKE 条件
     */
    public static <T> LambdaQueryWrapper<T> likeIfPresent(LambdaQueryWrapper<T> wrapper,
                                                          SFunction<T, ?> column, String value) {
        if (value != null && !value.isEmpty()) {
            wrapper.like(column, value);
        }
        return wrapper;
    }

    /**
     * 构建 Lambda 查询条件：当集合非空时添加 IN 条件
     */
    public static <T> LambdaQueryWrapper<T> inIfPresent(LambdaQueryWrapper<T> wrapper,
                                                        SFunction<T, ?> column, Collection<?> values) {
        if (values != null && !values.isEmpty()) {
            wrapper.in(column, values);
        }
        return wrapper;
    }

    /**
     * 构建 Lambda 查询条件：当值非空时添加 BETWEEN 条件
     */
    public static <T> LambdaQueryWrapper<T> betweenIfPresent(LambdaQueryWrapper<T> wrapper,
                                                             SFunction<T, ?> column, Object begin, Object end) {
        if (begin != null && end != null) {
            wrapper.between(column, begin, end);
        } else if (begin != null) {
            wrapper.ge(column, begin);
        } else if (end != null) {
            wrapper.le(column, end);
        }
        return wrapper;
    }

}
