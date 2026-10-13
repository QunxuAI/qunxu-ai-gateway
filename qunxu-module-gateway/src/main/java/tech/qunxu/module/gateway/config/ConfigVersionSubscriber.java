package tech.qunxu.module.gateway.config;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * 配置失效通知订阅者：监听 Redis 频道 {@value ConfigVersionPublisher#CHANNEL}，
 * 收到其它实例发布的变更通知后立即清空本实例的配置缓存。
 *
 * <p>与 {@link ConfigVersionPublisher} 构成「写后广播 / 收后失效」的多实例一致性闭环；
 * 发布方自身也会收到自己发出的消息，重复失效仅是一次廉价的缓存清空，不影响正确性。</p>
 *
 * <p>订阅在 {@link ApplicationReadyEvent} 后建立（此时 Redis 连接工厂与各配置服务均已就绪），
 * 并以指数退避<b>无限重连</b>：Redis 抖动期间丢失的通知由 Caffeine TTL 兜底，恢复后自动续订。
 * 无 Redis 环境（容器 Bean 不存在）下打印提示并降级为「仅本实例生效」，不影响启动。</p>
 */
@Component
public class ConfigVersionSubscriber {

    private static final Logger log = LoggerFactory.getLogger(ConfigVersionSubscriber.class);

    private final ObjectProvider<ReactiveRedisMessageListenerContainer> containerProvider;
    private final ConfigCacheInvalidator invalidator;

    private volatile Disposable subscription;

    public ConfigVersionSubscriber(ObjectProvider<ReactiveRedisMessageListenerContainer> containerProvider,
                                   ConfigCacheInvalidator invalidator) {
        this.containerProvider = containerProvider;
        this.invalidator = invalidator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ReactiveRedisMessageListenerContainer container = containerProvider.getIfAvailable();
        if (container == null) {
            log.info("未装配 Redis 消息监听容器，配置热失效仅本实例生效（其它实例将在缓存 TTL 后收敛）");
            return;
        }
        if (subscription != null && !subscription.isDisposed()) {
            return;
        }
        subscription = container.receive(ChannelTopic.of(ConfigVersionPublisher.CHANNEL))
                .doOnSubscribe(s -> log.info("已订阅配置失效频道 {}", ConfigVersionPublisher.CHANNEL))
                .doOnNext(this::onMessage)
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(5))
                        .maxBackoff(Duration.ofMinutes(1))
                        .doBeforeRetry(signal -> log.warn("配置失效订阅中断，准备第 {} 次重连: {}",
                                signal.totalRetries() + 1, String.valueOf(signal.failure()))))
                .subscribe(
                        message -> { },
                        e -> log.error("配置失效订阅已终止，后续变更需等待缓存 TTL: {}", e.toString()));
    }

    private void onMessage(ReactiveSubscription.Message<String, String> message) {
        String payload = message.getMessage();
        log.info("收到配置失效通知: {}", payload);
        invalidator.invalidateLocal("remote:" + payload)
                .subscribe(null, e -> log.warn("处理配置失效通知({})出错: {}", payload, e.toString()));
    }

    @PreDestroy
    public void stop() {
        Disposable current = subscription;
        if (current != null && !current.isDisposed()) {
            current.dispose();
            log.debug("已取消配置失效频道订阅");
        }
        subscription = null;
    }

    /** 当前订阅是否处于活跃状态，供健康检查与测试断言使用。 */
    public boolean isActive() {
        Disposable current = subscription;
        return current != null && !current.isDisposed();
    }
}
