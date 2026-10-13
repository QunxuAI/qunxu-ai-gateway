package tech.qunxu.module.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 网关模块装配配置。
 *
 * <p>集中开启网关模块所需的全局开关，避免改动 qunxu-server 启动类：</p>
 * <ul>
 *   <li>{@link EnableScheduling}：健康主动探测（{@code ActiveProbeScheduler}）等定时任务；</li>
 *   <li>{@link EnableCaching}：供应商/通道/模型配置的 Caffeine 本地缓存；</li>
 *   <li>{@link EnableConfigurationProperties}：绑定 {@code gateway.*} 配置项。</li>
 * </ul>
 */
@Configuration
@EnableScheduling
@EnableCaching
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayModuleConfiguration {
}
