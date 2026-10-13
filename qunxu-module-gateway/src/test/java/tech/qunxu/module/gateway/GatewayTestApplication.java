package tech.qunxu.module.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration;

/**
 * 网关模块测试用启动类（仅存在于 test 源码）。
 *
 * <p>网关模块已并入 QunxuAI（真实启动类为 {@code qunxu-server} 的
 * {@code QunxuServerApplication}），本类仅供模块内的 {@code @SpringBootTest}
 * 集成测试装配最小上下文：H2 内存库 + 内存健康/限流存储，不依赖真实 MySQL/Redis。</p>
 *
 * <p>排除项说明：</p>
 * <ul>
 *   <li>{@link ManagementWebSecurityAutoConfiguration}：其管理端点过滤器链匹配 any request，
 *       与 {@code QunxuSecurityAutoConfiguration} 冲突（UnreachableFilterChainException）；
 *       测试不访问 actuator，直接排除。</li>
 *   <li>{@link UserDetailsServiceAutoConfiguration}：与 Token 认证同理，不需要内存用户。</li>
 * </ul>
 *
 * <p>{@code @SpringBootTest} 未显式指定 {@code classes} 时会向上查找本类作为配置源。</p>
 */
@SpringBootApplication(
        exclude = {
                ManagementWebSecurityAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        })
public class GatewayTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayTestApplication.class, args);
    }
}
