package tech.qunxu.module.gateway.audit;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import tech.qunxu.module.gateway.persistence.entity.AuditLogEntity;
import tech.qunxu.module.gateway.persistence.service.AuditLogService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * 审计查询服务：提供多维过滤分页与 Token 用量聚合。
 *
 * <p>底层为 MyBatis-Plus <b>阻塞 JDBC</b>，故统一以 {@link Schedulers#boundedElastic()} 卸载为 {@link Mono}，
 * 避免阻塞 Netty 事件循环。分页查询按 {@code created_at} 倒序；用量聚合按维度分组、按总 token 倒序。</p>
 *
 * <p>聚合结果以列别名读取，并对键名做大小写兜底（不同 DB 对未加引号标识符的大小写处理不同），
 * 保证 H2（测试）与 MySQL（生产）一致可用。</p>
 */
@Service
public class AuditQueryService {

    private static final String ALIAS_KEY = "stat_key";
    private static final String ALIAS_CALLS = "calls";
    private static final String ALIAS_PROMPT = "prompt_tokens_sum";
    private static final String ALIAS_COMPLETION = "completion_tokens_sum";
    private static final String ALIAS_TOTAL = "total_tokens_sum";

    private final AuditLogService auditLogService;

    public AuditQueryService(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /** 多维过滤分页查询审计记录（按 {@code created_at} 倒序）。 */
    public Mono<AuditPage> query(AuditQuery q) {
        AuditQuery query = q != null ? q : AuditQuery.empty();
        int pageNo = query.normalizedPage();
        int size = query.normalizedSize();
        return Mono.fromCallable(() -> {
                    QueryWrapper<AuditLogEntity> w = Wrappers.<AuditLogEntity>query();
                    applyFilters(w, query);
                    w.orderByDesc("created_at");
                    Page<AuditLogEntity> result = auditLogService.page(new Page<>(pageNo, size), w);
                    List<AuditLogEntity> items = result != null && result.getRecords() != null
                            ? result.getRecords() : List.of();
                    long total = result != null ? result.getTotal() : 0L;
                    return new AuditPage(items, total, pageNo, size);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 按维度聚合 Token 用量（调用次数与各段 token 合计），按总 token 倒序。 */
    public Mono<List<TokenUsageStat>> usage(UsageDimension dimension, AuditQuery q) {
        UsageDimension dim = dimension != null ? dimension : UsageDimension.DAY;
        AuditQuery query = q != null ? q : AuditQuery.empty();
        return Mono.fromCallable(() -> {
                    QueryWrapper<AuditLogEntity> w = Wrappers.<AuditLogEntity>query();
                    w.select(dim.expression() + " AS " + ALIAS_KEY,
                            "COUNT(*) AS " + ALIAS_CALLS,
                            "COALESCE(SUM(prompt_tokens), 0) AS " + ALIAS_PROMPT,
                            "COALESCE(SUM(completion_tokens), 0) AS " + ALIAS_COMPLETION,
                            "COALESCE(SUM(total_tokens), 0) AS " + ALIAS_TOTAL);
                    applyFilters(w, query);
                    w.groupBy(dim.expression());
                    w.orderByDesc(ALIAS_TOTAL);
                    List<Map<String, Object>> rows = auditLogService.listMaps(w);
                    if (rows == null || rows.isEmpty()) {
                        return List.<TokenUsageStat>of();
                    }
                    return rows.stream().map(AuditQueryService::toStat).toList();
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 将查询条件翻译为等值/区间过滤；空值跳过。协议与状态统一大写以匹配落库形态。 */
    private void applyFilters(QueryWrapper<AuditLogEntity> w, AuditQuery q) {
        if (hasText(q.callerId())) {
            w.eq("caller_id", q.callerId());
        }
        if (hasText(q.tenant())) {
            w.eq("tenant", q.tenant());
        }
        if (hasText(q.logicalModel())) {
            w.eq("logical_model", q.logicalModel());
        }
        if (hasText(q.protocol())) {
            w.eq("protocol", q.protocol().trim().toUpperCase());
        }
        if (hasText(q.status())) {
            w.eq("status", q.status().trim().toUpperCase());
        }
        if (q.channelId() != null) {
            w.eq("channel_id", q.channelId());
        }
        if (q.providerId() != null) {
            w.eq("provider_id", q.providerId());
        }
        if (hasText(q.errorCode())) {
            w.eq("error_code", q.errorCode());
        }
        if (hasText(q.traceId())) {
            w.eq("trace_id", q.traceId());
        }
        if (q.from() != null) {
            w.ge("created_at", q.from());
        }
        if (q.to() != null) {
            w.le("created_at", q.to());
        }
    }

    private static TokenUsageStat toStat(Map<String, Object> row) {
        Object key = pick(row, ALIAS_KEY);
        return new TokenUsageStat(
                key != null ? key.toString() : null,
                toLong(pick(row, ALIAS_CALLS)),
                toLong(pick(row, ALIAS_PROMPT)),
                toLong(pick(row, ALIAS_COMPLETION)),
                toLong(pick(row, ALIAS_TOTAL)));
    }

    /** 大小写兜底取值：先精确匹配别名，再忽略大小写匹配（适配不同 DB 的标识符大小写策略）。 */
    private static Object pick(Map<String, Object> row, String key) {
        if (row == null) {
            return null;
        }
        if (row.containsKey(key)) {
            return row.get(key);
        }
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static long toLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
