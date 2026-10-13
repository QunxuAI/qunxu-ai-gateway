package tech.qunxu.module.gateway.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关模块的 MyBatis-Plus 增强。
 *
 * <p>Qunxu 的 {@code qunxu-spring-boot-starter-mybatis} 已注册全局
 * {@link MybatisPlusInterceptor}（含分页插件）与字段自动填充处理器。为避免 Bean 冲突，
 * 本类不再自行注册拦截器/填充器，而是通过 {@link BeanPostProcessor} 向既有拦截器链
 * 追加网关所需的两个内置拦截器：</p>
 * <ul>
 *   <li>{@link OptimisticLockerInnerInterceptor}：支撑 {@code @Version} 乐观锁（供应商/通道等实体）；</li>
 *   <li>{@link BlockAttackInnerInterceptor}：禁止无 {@code where} 条件的全表更新/删除。</li>
 * </ul>
 *
 * <p>网关实体的 {@code created_at / updated_at} 由数据库 {@code DEFAULT CURRENT_TIMESTAMP} /
 * {@code ON UPDATE CURRENT_TIMESTAMP} 兜底填充，不依赖 MetaObjectHandler。</p>
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public BeanPostProcessor mybatisPlusInterceptorEnhancer() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof MybatisPlusInterceptor interceptor) {
                    interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
                    interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
                }
                return bean;
            }
        };
    }
}
