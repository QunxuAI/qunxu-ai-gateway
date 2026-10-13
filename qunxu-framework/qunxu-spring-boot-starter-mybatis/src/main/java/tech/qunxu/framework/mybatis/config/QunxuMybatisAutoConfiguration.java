package tech.qunxu.framework.mybatis.config;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import tech.qunxu.framework.mybatis.core.handler.DefaultDBFieldHandler;
import tech.qunxu.framework.security.core.SecurityUtils;

/**
 * MyBatis Plus 自动配置。
 * <p>
 * 注册分页插件、字段自动填充处理器，并扫描项目内所有 Mapper 接口。
 * 多租户、数据权限等业务组件通过 {@code InnerInterceptor} Bean 或
 * {@code addInterceptor} 方法向拦截器链注册自身能力。
 */
@AutoConfiguration(before = MybatisPlusAutoConfiguration.class) // 先于 MyBatis Plus 自动配置，避免 Mapper 扫描告警
@MapperScan(value = "tech.qunxu", annotationClass = Mapper.class)
public class QunxuMybatisAutoConfiguration {

    static {
        // 注册登录用户提供器：创建人、更新人字段自动填充当前登录用户
        DefaultDBFieldHandler.setLoginUserProvider(SecurityUtils::getLoginUserId);
    }

    /**
     * MyBatis Plus 拦截器链：默认注册分页插件
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        return interceptor;
    }

    /**
     * 字段自动填充处理器：创建人、创建时间等
     */
    @Bean
    public DefaultDBFieldHandler defaultDBFieldHandler() {
        return new DefaultDBFieldHandler();
    }

}
