package tech.qunxu.module.gateway.admin;

import tech.qunxu.module.gateway.config.ConfigVersionPublisher;
import tech.qunxu.module.gateway.config.ConfigVersionSubscriber;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.guardrail.SensitiveWordService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 配置缓存管理接口（{@code /admin/cache}）。
 *
 * <p>正常路径下配置写入会自动失效缓存并广播，本接口用于两类例外场景：</p>
 * <ul>
 *   <li>直接改数据库（绕过管理接口）后手动触发全实例失效；</li>
 *   <li>怀疑某实例缓存陈旧时仅失效本实例（{@code /invalidate-local}），以便对比定位问题。</li>
 * </ul>
 *
 * <p>{@code /status} 返回缓存与限流子系统的运行时概况，便于快速确认「Redis pub/sub 是否在线」
 * 「敏感词库条数」「限流用的是 Redis 还是进程内实现」。</p>
 */
@RestController
@RequestMapping("/admin-api/gateway/cache")
public class CacheAdminController {

    private final ConfigVersionPublisher publisher;
    private final ConfigVersionSubscriber subscriber;
    private final SensitiveWordService sensitiveWordService;
    private final GatewayProperties properties;

    public CacheAdminController(ConfigVersionPublisher publisher,
                                ConfigVersionSubscriber subscriber,
                                SensitiveWordService sensitiveWordService,
                                GatewayProperties properties) {
        this.publisher = publisher;
        this.subscriber = subscriber;
        this.sensitiveWordService = sensitiveWordService;
        this.properties = properties;
    }

    /**
     * 失效配置缓存并向其它实例广播。
     *
     * @param type 变更类别，取值见 {@link ConfigVersionPublisher} 的 {@code TYPE_*} 常量；
     *             缺省或未知值触发<b>全量</b>失效
     */
    @PostMapping("/invalidate")
    public Mono<String> invalidate(@RequestParam(required = false) String type) {
        String kind = type == null || type.isBlank() ? "all" : type.trim();
        return publisher.publish(kind).thenReturn("已失效并广播: " + kind);
    }

    /** 仅失效本实例缓存，不广播（用于定位单实例缓存陈旧问题）。 */
    @PostMapping("/invalidate-local")
    public Mono<String> invalidateLocal(@RequestParam(required = false) String type) {
        String kind = type == null || type.isBlank() ? "all" : type.trim();
        return publisher.invalidateLocal(kind).thenReturn("已失效本实例: " + kind);
    }

    /** 缓存与限流子系统运行时概况。 */
    @GetMapping("/status")
    public Mono<CacheStatus> status() {
        return Mono.fromSupplier(() -> new CacheStatus(
                sensitiveWordService.size(),
                subscriber.isActive(),
                ConfigVersionPublisher.CHANNEL,
                properties.getRateLimit().getStore(),
                properties.getRateLimit().isEnabled(),
                properties.getQuota().isEnabled(),
                properties.getHealth().getStore(),
                properties.getHealth().isEnabled()));
    }

    /**
     * 缓存与限流概况。
     *
     * @param sensitiveWords   当前敏感词快照条数（种子库 + 数据库启用词条）
     * @param pubsubActive     Redis 配置失效订阅是否在线；{@code false} 表示变更仅本实例立即生效
     * @param pubsubChannel    配置失效广播频道名
     * @param rateLimitStore   限流存储实现：{@code redis}（多实例共享）或 {@code in-memory}（单机）
     * @param rateLimitEnabled 限流是否启用
     * @param quotaEnabled     每日 Token 配额是否启用
     * @param healthStore      健康存储实现：{@code redis} 或 {@code in-memory}
     * @param healthEnabled    健康自愈是否启用
     */
    public record CacheStatus(int sensitiveWords, boolean pubsubActive, String pubsubChannel,
                              String rateLimitStore, boolean rateLimitEnabled, boolean quotaEnabled,
                              String healthStore, boolean healthEnabled) {
    }
}
