package tech.qunxu.module.gateway.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tech.qunxu.module.gateway.persistence.entity.ApiKeyEntity;
import tech.qunxu.module.gateway.persistence.mapper.ApiKeyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 网关 APIKey 鉴权快照服务（数据面热路径）。
 *
 * <p>把数据库 {@code gateway_api_key} 表启用且未过期的 APIKey 载入<b>不可变内存快照</b>
 * （{@link AtomicReference} 原子替换，请求热路径零 I/O、无锁），供 {@link CallerIdentityResolver}
 * 按 {@code Authorization: Bearer <key>} 的 SHA-256 摘要匹配调用人身份。与限流规则/敏感词库
 * 同款「快照 + 重建」模式：管理端写入后经 {@code ConfigVersionPublisher} 广播
 * {@code api-key} 类型失效，本实例与其它实例立即重建快照，秒级生效。</p>
 *
 * <p>安全约定：</p>
 * <ul>
 *   <li><b>只存摘要</b>：数据库仅保存密钥的 SHA-256 摘要，快照中同样只放摘要 → 明文永不驻留内存；</li>
 *   <li><b>停用/删除即时失效</b>：停用或删除行在重建快照时被剔除；</li>
 *   <li><b>过期运行期校验</b>：快照行带 {@code expiresAt}，解析时实时判断，过期即视为无效；</li>
 *   <li><b>最近使用异步回写</b>：解析成功后经 boundedElastic 异步更新 {@code last_used_at}
 *       （每密钥每分钟至多落一次库），失败静默不影响请求。</li>
 * </ul>
 */
@Component
public class ApiKeySnapshotService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeySnapshotService.class);

    /** 密钥摘要匹配后的调用人身份快照行。 */
    public record ApiKeyAuth(long keyId, long userId, String userName, String keyName,
                             String tenant, LocalDateTime expiresAt) {
    }

    /** last_used_at 异步回写的最小间隔（毫秒），避免高 QPS 下每请求都 UPDATE。 */
    private static final long LAST_USED_THROTTLE_MS = 60_000;

    private final ApiKeyMapper mapper;
    private final AtomicReference<Map<String, ApiKeyAuth>> snapshot = new AtomicReference<>(Map.of());
    /** keyId -> 最近一次回写时间戳，用于节流。 */
    private final ConcurrentHashMap<Long, Long> lastUsedFlushedAt = new ConcurrentHashMap<>();

    public ApiKeySnapshotService(ApiKeyMapper mapper) {
        this.mapper = mapper;
    }

    /** 应用就绪后首次载入（表不存在/不可用时静默保持空快照，数据面回退静态令牌/匿名）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void loadOnReady() {
        refresh().subscribe(null, e -> log.warn("APIKey 快照首次载入失败: {}", e.toString()));
    }

    /** 重建快照（读库，阻塞 JDBC 卸载到 boundedElastic）；失败保持现有快照。 */
    public Mono<Integer> refresh() {
        return Mono.fromCallable(this::loadSync)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("重建 APIKey 快照失败，沿用现有快照: {}", e.toString());
                    return Mono.just(snapshot.get().size());
                });
    }

    private int loadSync() {
        var rows = mapper.selectList(Wrappers.<ApiKeyEntity>query()
                .eq("status", 1)
                .and(w -> w.isNull("expires_at").or().gt("expires_at", LocalDateTime.now()))
                .orderByAsc("id"));
        Map<String, ApiKeyAuth> built = new java.util.HashMap<>();
        for (ApiKeyEntity row : rows) {
            // 双保险：代码层再校验启用状态，避免脏数据进入快照
            if (row.getStatus() == null || row.getStatus() != 1) {
                continue;
            }
            String hash = row.getApiKeyHash();
            if (hash == null || hash.isBlank()) {
                log.debug("跳过缺少摘要的 APIKey: id={}", row.getId());
                continue;
            }
            built.put(hash, new ApiKeyAuth(row.getId(), row.getUserId(),
                    row.getUserName(), row.getName(), row.getTenant(), row.getExpiresAt()));
        }
        snapshot.set(Map.copyOf(built));
        if (log.isDebugEnabled()) {
            log.debug("APIKey 快照已重建，共 {} 条", built.size());
        }
        return built.size();
    }

    // ------------------------------------------------------------ 热路径解析（零 I/O）

    /**
     * 按 Bearer 令牌解析调用人身份。快照未就绪、摘要不匹配、已停用或已过期时返回空。
     *
     * @param bearerToken 请求头 {@code Authorization: Bearer xxx} 中的令牌
     */
    public Optional<ApiKeyAuth> resolve(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return Optional.empty();
        }
        ApiKeyAuth auth = snapshot.get().get(sha256Hex(bearerToken));
        if (auth == null) {
            return Optional.empty();
        }
        // 过期实时判断：快照可能在重建周期内跨过过期时刻
        if (auth.expiresAt() != null && auth.expiresAt().isBefore(LocalDateTime.now())) {
            return Optional.empty();
        }
        touchLastUsed(auth.keyId());
        return Optional.of(auth);
    }

    /** 当前快照中有效密钥条数（健康检查/测试用）。 */
    public int size() {
        return snapshot.get().size();
    }

    /**
     * 异步回写 {@code last_used_at}（节流：每密钥至少间隔 {@value #LAST_USED_THROTTLE_MS} 毫秒）。
     * 失败静默降级，不影响请求主链路。
     */
    private void touchLastUsed(long keyId) {
        long now = System.currentTimeMillis();
        Long flushed = lastUsedFlushedAt.get(keyId);
        if (flushed != null && now - flushed < LAST_USED_THROTTLE_MS) {
            return;
        }
        Long previous = lastUsedFlushedAt.putIfAbsent(keyId, now);
        if (previous != null && now - previous < LAST_USED_THROTTLE_MS) {
            return;
        }
        lastUsedFlushedAt.put(keyId, now);
        Mono.fromRunnable(() -> mapper.update(null,
                        Wrappers.<ApiKeyEntity>lambdaUpdate()
                                .eq(ApiKeyEntity::getId, keyId)
                                .set(ApiKeyEntity::getLastUsedAt, LocalDateTime.now())))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(null, e -> {
                    if (log.isDebugEnabled()) {
                        log.debug("回写 APIKey 最近使用时间失败: id={} {}", keyId, e.toString());
                    }
                });
    }

    // ------------------------------------------------------------ 工具

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
