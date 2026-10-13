package tech.qunxu.module.gateway.observability;

import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * Reactor 上下文与日志 MDC 的桥接配置。
 *
 * <p>注册一个 {@code traceId} 的 {@link io.micrometer.context.ThreadLocalAccessor}，并开启 Reactor
 * 自动上下文传播。此后凡 Reactor Context 中带有 {@code traceId}（由 {@link TraceIdFilter} 写入），
 * 在信号执行的线程上都会自动恢复到 MDC，从而让 {@code %X{traceId}} 贯穿所有异步日志——
 * 包括 {@code flatMap}、{@code boundedElastic} 上的 DB 调用、Reactor Netty 客户端回调等。</p>
 */
@Configuration
public class ReactorMdcConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ReactorMdcConfiguration.class);

    @PostConstruct
    public void enableContextPropagation() {
        String key = TraceIdFilter.TRACE_ID_KEY;
        ContextRegistry.getInstance().registerThreadLocalAccessor(
                key,
                () -> MDC.get(key),
                value -> MDC.put(key, value),
                () -> MDC.remove(key));
        Hooks.enableAutomaticContextPropagation();
        log.info("Reactor automatic context propagation enabled (MDC key: {})", key);
    }

    @PreDestroy
    public void disableContextPropagation() {
        Hooks.disableAutomaticContextPropagation();
        ContextRegistry.getInstance().removeThreadLocalAccessor(TraceIdFilter.TRACE_ID_KEY);
    }
}
