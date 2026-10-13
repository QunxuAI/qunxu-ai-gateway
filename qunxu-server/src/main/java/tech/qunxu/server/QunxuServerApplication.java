package tech.qunxu.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Qunxu AI 后端服务启动类。
 * <p>
 * 通过 {@code scanBasePackages} 扫描 server 与 module 包，加载各业务模块的组件；
 * 框架 starter 的自动配置通过 {@code META-INF/spring/...AutoConfiguration.imports} 加载。
 * <p>
 * 排除 Spring Security 默认的基于内存用户的自动配置：本项目采用 Token 认证，
 * 登录用户由 OAuth2 令牌在 Redis 中维护，无需表单登录与内存用户。
 */
@SpringBootApplication(
        scanBasePackages = {"tech.qunxu.server", "tech.qunxu.module"},
        exclude = UserDetailsServiceAutoConfiguration.class)
public class QunxuServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(QunxuServerApplication.class, args);
    }

}
