package tech.qunxu.module.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关自定义配置，绑定 {@code gateway.*}。集中管理下游超时、负载均衡、健康自愈、
 * 审计、限流、配额、安全围栏、内容审计与凭证加密等参数。
 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private Security security = new Security();
    private Downstream downstream = new Downstream();
    private LoadBalance loadBalance = new LoadBalance();
    private Health health = new Health();
    private Audit audit = new Audit();
    private RateLimit rateLimit = new RateLimit();
    private Quota quota = new Quota();
    private Guardrail guardrail = new Guardrail();
    private Moderation moderation = new Moderation();
    private Crypto crypto = new Crypto();
    private Admin admin = new Admin();
    private Config config = new Config();
    private Sticky sticky = new Sticky();

    public Security getSecurity() { return security; }
    public void setSecurity(Security security) { this.security = security; }
    public Downstream getDownstream() { return downstream; }
    public void setDownstream(Downstream downstream) { this.downstream = downstream; }
    public LoadBalance getLoadBalance() { return loadBalance; }
    public void setLoadBalance(LoadBalance loadBalance) { this.loadBalance = loadBalance; }
    public Health getHealth() { return health; }
    public void setHealth(Health health) { this.health = health; }
    public Audit getAudit() { return audit; }
    public void setAudit(Audit audit) { this.audit = audit; }
    public RateLimit getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }
    public Quota getQuota() { return quota; }
    public void setQuota(Quota quota) { this.quota = quota; }
    public Admin getAdmin() { return admin; }
    public void setAdmin(Admin admin) { this.admin = admin; }
    public Guardrail getGuardrail() { return guardrail; }
    public void setGuardrail(Guardrail guardrail) { this.guardrail = guardrail; }
    public Moderation getModeration() { return moderation; }
    public void setModeration(Moderation moderation) { this.moderation = moderation; }
    public Crypto getCrypto() { return crypto; }
    public void setCrypto(Crypto crypto) { this.crypto = crypto; }
    public Config getConfig() { return config; }
    public void setConfig(Config config) { this.config = config; }
    public Sticky getSticky() { return sticky; }
    public void setSticky(Sticky sticky) { this.sticky = sticky; }

    /** 上游鉴权配置。 */
    public static class Security {
        private boolean enabled = false;
        private String issuerUri = "";
        private String jwkSetUri = "";
        private String staticTokens = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getIssuerUri() { return issuerUri; }
        public void setIssuerUri(String issuerUri) { this.issuerUri = issuerUri; }
        public String getJwkSetUri() { return jwkSetUri; }
        public void setJwkSetUri(String jwkSetUri) { this.jwkSetUri = jwkSetUri; }
        public String getStaticTokens() { return staticTokens; }
        public void setStaticTokens(String staticTokens) { this.staticTokens = staticTokens; }
    }

    /** 下游 HTTP/WS 客户端配置。 */
    public static class Downstream {
        private int connectTimeoutMs = 10000;
        private int readTimeoutMs = 120000;
        private String maxInMemorySize = "16MB";
        private int maxRetries = 2;

        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
        public String getMaxInMemorySize() { return maxInMemorySize; }
        public void setMaxInMemorySize(String maxInMemorySize) { this.maxInMemorySize = maxInMemorySize; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    }

    /** 负载均衡配置。 */
    public static class LoadBalance {
        private String defaultStrategy = "WEIGHTED_ROUND_ROBIN";

        public String getDefaultStrategy() { return defaultStrategy; }
        public void setDefaultStrategy(String defaultStrategy) { this.defaultStrategy = defaultStrategy; }
    }

    /** 健康监控与自愈配置。 */
    public static class Health {
        private boolean enabled = true;
        /** 健康状态存储实现：redis（多实例共享，默认）| in-memory（单机/测试）。 */
        private String store = "redis";
        private long probeIntervalMs = 30000;
        private long probeTimeoutMs = 8000;
        private int failureThreshold = 5;
        private double errorRateThreshold = 0.5;
        private int windowSize = 20;
        private long ejectCooldownMs = 60000;
        private int recoverySuccessThreshold = 2;
        private boolean distributedLock = true;
        /** EWMA 平均时延平滑系数（0~1，越大越重视新样本），用于最小延迟负载均衡。 */
        private double latencyAlpha = 0.3;
        /** Redis 健康 hash 的 TTL（秒），防止已删除通道的键长期驻留；<=0 表示不设过期。 */
        private long healthTtlSeconds = 86400;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getStore() { return store; }
        public void setStore(String store) { this.store = store; }
        public long getProbeIntervalMs() { return probeIntervalMs; }
        public void setProbeIntervalMs(long probeIntervalMs) { this.probeIntervalMs = probeIntervalMs; }
        public long getProbeTimeoutMs() { return probeTimeoutMs; }
        public void setProbeTimeoutMs(long probeTimeoutMs) { this.probeTimeoutMs = probeTimeoutMs; }
        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
        public double getErrorRateThreshold() { return errorRateThreshold; }
        public void setErrorRateThreshold(double errorRateThreshold) { this.errorRateThreshold = errorRateThreshold; }
        public int getWindowSize() { return windowSize; }
        public void setWindowSize(int windowSize) { this.windowSize = windowSize; }
        public long getEjectCooldownMs() { return ejectCooldownMs; }
        public void setEjectCooldownMs(long ejectCooldownMs) { this.ejectCooldownMs = ejectCooldownMs; }
        public int getRecoverySuccessThreshold() { return recoverySuccessThreshold; }
        public void setRecoverySuccessThreshold(int recoverySuccessThreshold) { this.recoverySuccessThreshold = recoverySuccessThreshold; }
        public boolean isDistributedLock() { return distributedLock; }
        public void setDistributedLock(boolean distributedLock) { this.distributedLock = distributedLock; }
        public double getLatencyAlpha() { return latencyAlpha; }
        public void setLatencyAlpha(double latencyAlpha) { this.latencyAlpha = latencyAlpha; }
        public long getHealthTtlSeconds() { return healthTtlSeconds; }
        public void setHealthTtlSeconds(long healthTtlSeconds) { this.healthTtlSeconds = healthTtlSeconds; }
    }

    /** 审计配置。 */
    public static class Audit {
        private boolean enabled = true;
        private int batchSize = 50;
        private long flushIntervalMs = 1000;
        private int queueCapacity = 10000;
        private int maxBodyLength = 8192;
        private boolean maskSensitive = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public long getFlushIntervalMs() { return flushIntervalMs; }
        public void setFlushIntervalMs(long flushIntervalMs) { this.flushIntervalMs = flushIntervalMs; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
        public int getMaxBodyLength() { return maxBodyLength; }
        public void setMaxBodyLength(int maxBodyLength) { this.maxBodyLength = maxBodyLength; }
        public boolean isMaskSensitive() { return maskSensitive; }
        public void setMaskSensitive(boolean maskSensitive) { this.maskSensitive = maskSensitive; }
    }

    /**
     * 限流配置（Lua 令牌桶，按调用人 / 调用人+模型两级）。
     *
     * <p>许可数解析优先级：调用人覆盖 &gt; 租户覆盖 &gt; 全局默认；模型级同理。
     * 许可数 {@code <=0} 表示该维度不限制。</p>
     */
    public static class RateLimit {
        private boolean enabled = true;
        private int defaultPermitsPerMinute = 600;
        /** 限流计数存储：redis（多实例共享，默认）| in-memory（单机/测试）。 */
        private String store = "redis";
        /** 令牌桶容量 = 每分钟许可数 × 该系数（>=1 时允许等比短时突发）。 */
        private double burstRatio = 1.0d;
        /** 存储不可用时是否失败开放（默认放行，避免 Redis 抖动阻断业务）。 */
        private boolean failOpen = true;
        /** 是否额外按「调用人 + 模型」维度限流。 */
        private boolean perModelEnabled = true;
        private int defaultModelPermitsPerMinute = 300;
        /** 调用人级许可覆盖：callerId → permits/min。 */
        private Map<String, Integer> callerOverrides = new LinkedHashMap<>();
        /** 租户级许可覆盖：tenant → permits/min。 */
        private Map<String, Integer> tenantOverrides = new LinkedHashMap<>();
        /** 模型级许可覆盖：model → permits/min。 */
        private Map<String, Integer> modelOverrides = new LinkedHashMap<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getDefaultPermitsPerMinute() { return defaultPermitsPerMinute; }
        public void setDefaultPermitsPerMinute(int defaultPermitsPerMinute) { this.defaultPermitsPerMinute = defaultPermitsPerMinute; }
        public String getStore() { return store; }
        public void setStore(String store) { this.store = store; }
        public double getBurstRatio() { return burstRatio; }
        public void setBurstRatio(double burstRatio) { this.burstRatio = burstRatio; }
        public boolean isFailOpen() { return failOpen; }
        public void setFailOpen(boolean failOpen) { this.failOpen = failOpen; }
        public boolean isPerModelEnabled() { return perModelEnabled; }
        public void setPerModelEnabled(boolean perModelEnabled) { this.perModelEnabled = perModelEnabled; }
        public int getDefaultModelPermitsPerMinute() { return defaultModelPermitsPerMinute; }
        public void setDefaultModelPermitsPerMinute(int defaultModelPermitsPerMinute) { this.defaultModelPermitsPerMinute = defaultModelPermitsPerMinute; }
        public Map<String, Integer> getCallerOverrides() { return callerOverrides; }
        public void setCallerOverrides(Map<String, Integer> callerOverrides) { this.callerOverrides = callerOverrides != null ? callerOverrides : new LinkedHashMap<>(); }
        public Map<String, Integer> getTenantOverrides() { return tenantOverrides; }
        public void setTenantOverrides(Map<String, Integer> tenantOverrides) { this.tenantOverrides = tenantOverrides != null ? tenantOverrides : new LinkedHashMap<>(); }
        public Map<String, Integer> getModelOverrides() { return modelOverrides; }
        public void setModelOverrides(Map<String, Integer> modelOverrides) { this.modelOverrides = modelOverrides != null ? modelOverrides : new LinkedHashMap<>(); }
    }

    /**
     * Token 配额配置：按调用人（缺省按租户）限制每日累计消耗的 total tokens。
     *
     * <p>配额计数存于 Redis（{@code gw:quota:tokens:{scope}:{yyyyMMdd}}，跨实例共享、按日滚动）；
     * Redis 不可用时降级为进程内计数（单机近似）并失败开放。限额 {@code <=0} 表示不限制。</p>
     */
    public static class Quota {
        private boolean enabled = false;
        /** 每日 Token 配额默认值；<=0 表示不限。 */
        private long defaultDailyTokens = 0L;
        /** 配额作用维度：caller（按调用人，默认）| tenant（按租户汇总）。 */
        private String scope = "caller";
        /** 调用人级配额覆盖：callerId → daily tokens。 */
        private Map<String, Long> callerOverrides = new LinkedHashMap<>();
        /** 租户级配额覆盖：tenant → daily tokens。 */
        private Map<String, Long> tenantOverrides = new LinkedHashMap<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getDefaultDailyTokens() { return defaultDailyTokens; }
        public void setDefaultDailyTokens(long defaultDailyTokens) { this.defaultDailyTokens = defaultDailyTokens; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
        public Map<String, Long> getCallerOverrides() { return callerOverrides; }
        public void setCallerOverrides(Map<String, Long> callerOverrides) { this.callerOverrides = callerOverrides != null ? callerOverrides : new LinkedHashMap<>(); }
        public Map<String, Long> getTenantOverrides() { return tenantOverrides; }
        public void setTenantOverrides(Map<String, Long> tenantOverrides) { this.tenantOverrides = tenantOverrides != null ? tenantOverrides : new LinkedHashMap<>(); }
    }

    /** 管理接口配置。 */
    public static class Admin {
        /** 分页查询每页条数上限，防止一次拉取过多配置行。 */
        private int maxPageSize = 500;
        /** 是否在管理接口返回供应商凭据密文（默认关闭，仅返回是否已设置的标记）。 */
        private boolean exposeCredentials = false;

        public int getMaxPageSize() { return maxPageSize; }
        public void setMaxPageSize(int maxPageSize) { this.maxPageSize = maxPageSize; }
        public boolean isExposeCredentials() { return exposeCredentials; }
        public void setExposeCredentials(boolean exposeCredentials) { this.exposeCredentials = exposeCredentials; }
    }

    /** 安全围栏配置。 */
    public static class Guardrail {
        private boolean enabled = true;
        private int maxInputLength = 32768;
        private int maxOutputLength = 65536;
        /** 脱敏默认模式：star（等长 * 掩码，不可逆）或 placeholder（占位符 + 响应本地还原）。 */
        private String maskMode = "star";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxInputLength() { return maxInputLength; }
        public void setMaxInputLength(int maxInputLength) { this.maxInputLength = maxInputLength; }
        public int getMaxOutputLength() { return maxOutputLength; }
        public void setMaxOutputLength(int maxOutputLength) { this.maxOutputLength = maxOutputLength; }
        public String getMaskMode() { return maskMode; }
        public void setMaskMode(String maskMode) { this.maskMode = maskMode; }
    }

    /** 内容审计配置。 */
    public static class Moderation {
        private boolean enabled = true;
        private String defaultProvider = "builtin";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getDefaultProvider() { return defaultProvider; }
        public void setDefaultProvider(String defaultProvider) { this.defaultProvider = defaultProvider; }
    }

    /** 凭证加密配置。 */
    public static class Crypto {
        private String secret = "qunxu-default-dev-secret-change-me";

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
    }

    /**
     * 配置缓存与热失效配置。
     *
     * <p>{@code pubsubEnabled} 决定是否订阅 Redis 频道 {@code gw:config:version}：多实例部署下
     * 「写后广播 / 收后失效」依赖它才能秒级收敛；单机或无 Redis 的测试环境可关闭，此时仅靠
     * Caffeine TTL 收敛，且不会因连不上 Redis 而反复重连刷日志。</p>
     */
    public static class Config {
        private boolean pubsubEnabled = true;

        public boolean isPubsubEnabled() { return pubsubEnabled; }
        public void setPubsubEnabled(boolean pubsubEnabled) { this.pubsubEnabled = pubsubEnabled; }
    }

    /**
     * 会话级供应商粘性配置。
     *
     * <p>同一会话（同一调用人 + 会话 ID）的所有轮次固定复用首次选定的通道，避免多轮对话在供应商间漂移
     * 导致 Anthropic 思考块续传 / OpenAI Responses 加密续传数据失效。绑定存于 Redis（TTL 自动过期）；
     * Redis 不可用时按 {@code failOpen} 降级为普通负载均衡路由。</p>
     */
    public static class Sticky {
        /** 是否启用粘性会话路由。 */
        private boolean enabled = false;
        /** 绑定 TTL（秒），会话超时后自动释放。 */
        private long ttlSeconds = 3600;
        /** 从请求头读取会话 ID 的头部名称（客户端多轮对话需透传同一值）。 */
        private String headerName = "X-Session-Id";
        /** Redis 键前缀。 */
        private String redisKeyPrefix = "gw:sticky";
        /** Redis 不可用或查询异常时是否降级为普通路由（true）或拒绝请求（false）。 */
        private boolean failOpen = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
        public String getHeaderName() { return headerName; }
        public void setHeaderName(String headerName) { this.headerName = headerName; }
        public String getRedisKeyPrefix() { return redisKeyPrefix; }
        public void setRedisKeyPrefix(String redisKeyPrefix) { this.redisKeyPrefix = redisKeyPrefix; }
        public boolean isFailOpen() { return failOpen; }
        public void setFailOpen(boolean failOpen) { this.failOpen = failOpen; }
    }
}
