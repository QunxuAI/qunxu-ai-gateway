package tech.qunxu.module.gateway.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 下游 HTTP / WebSocket 客户端配置。WebClient 基于 Reactor Netty，配置连接/读取超时与内存缓冲上限，
 * 供各供应商适配器以绝对 URL 调用下游（含 SSE 流式）。
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient gatewayWebClient(GatewayProperties properties) {
        GatewayProperties.Downstream ds = properties.getDownstream();
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, ds.getConnectTimeoutMs())
                .responseTimeout(Duration.ofMillis(ds.getReadTimeoutMs()))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(ds.getReadTimeoutMs(), TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(ds.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)));

        int maxInMemory = (int) DataSize.parse(ds.getMaxInMemorySize()).toBytes();
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(maxInMemory))
                .build();

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }

    @Bean
    public WebSocketClient gatewayWebSocketClient(GatewayProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.getDownstream().getConnectTimeoutMs())
                .responseTimeout(Duration.ofMillis(properties.getDownstream().getReadTimeoutMs()));
        return new ReactorNettyWebSocketClient(httpClient);
    }
}
