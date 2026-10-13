package tech.qunxu.module.gateway.audit;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import tech.qunxu.module.gateway.persistence.entity.AuditLogEntity;
import tech.qunxu.module.gateway.persistence.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link AuditQueryService} 单元测试：mock {@link AuditLogService}，验证分页结果映射、分页参数归一化与封顶、
 * 过滤条件与排序构建、聚合维度表达式选择，以及聚合行到 {@link TokenUsageStat} 的映射（含列名大小写兜底）。
 */
class AuditQueryServiceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private AuditLogService auditLogService;
    private AuditQueryService service;

    @BeforeEach
    void setUp() {
        auditLogService = mock(AuditLogService.class);
        service = new AuditQueryService(auditLogService);
    }

    private static AuditLogEntity entity(long id, String caller, String model) {
        AuditLogEntity e = new AuditLogEntity();
        e.setId(id);
        e.setCallerId(caller);
        e.setLogicalModel(model);
        e.setStatus("SUCCESS");
        e.setCreatedAt(LocalDateTime.of(2026, 9, 10, 12, 0, 0));
        return e;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<QueryWrapper<AuditLogEntity>> wrapperCaptor() {
        return ArgumentCaptor.forClass(QueryWrapper.class);
    }

    @Test
    void queryMapsRecordsAndTotal() {
        Page<AuditLogEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(entity(1, "alice", "deepseek-chat"), entity(2, "bob", "glm-4")));
        page.setTotal(42L);
        doReturn(page).when(auditLogService).page(any(), any());

        AuditPage result = service.query(new AuditQuery(null, null, null, null, null,
                null, null, null, null, null, null, 1, 20)).block(TIMEOUT);

        assertThat(result).isNotNull();
        assertThat(result.items()).hasSize(2);
        assertThat(result.total()).isEqualTo(42L);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalPages()).isEqualTo(3);   // ceil(42/20)
    }

    @Test
    void queryAppliesDefaultPagingWhenUnset() {
        Page<AuditLogEntity> page = new Page<>(1, 20);
        page.setRecords(List.of());
        doReturn(page).when(auditLogService).page(any(), any());

        service.query(new AuditQuery(null, null, null, null, null,
                null, null, null, null, null, null, null, null)).block(TIMEOUT);

        ArgumentCaptor<Page> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(auditLogService).page(pageCaptor.capture(), any());
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1L);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(20L);
    }

    @Test
    void queryCapsSizeAtMaximum() {
        Page<AuditLogEntity> page = new Page<>(1, 200);
        page.setRecords(List.of());
        doReturn(page).when(auditLogService).page(any(), any());

        AuditPage result = service.query(new AuditQuery(null, null, null, null, null,
                null, null, null, null, null, null, 1, 1000)).block(TIMEOUT);

        assertThat(result.size()).isEqualTo(AuditQuery.MAX_SIZE);
        ArgumentCaptor<Page> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(auditLogService).page(pageCaptor.capture(), any());
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(200L);
    }

    @Test
    void queryBuildsFiltersAndOrdersByCreatedAtDesc() {
        Page<AuditLogEntity> page = new Page<>(1, 20);
        page.setRecords(List.of());
        doReturn(page).when(auditLogService).page(any(), any());

        service.query(new AuditQuery("alice", "team-a", "deepseek-chat", "openai", "success",
                10L, 100L, "PROVIDER_ERROR", "trace-1",
                LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 30, 0, 0),
                1, 20)).block(TIMEOUT);

        ArgumentCaptor<QueryWrapper<AuditLogEntity>> captor = wrapperCaptor();
        verify(auditLogService).page(any(), captor.capture());
        QueryWrapper<AuditLogEntity> w = captor.getValue();
        String sql = w.getSqlSegment();
        assertThat(sql).contains("caller_id").contains("tenant").contains("logical_model")
                .contains("channel_id").contains("provider_id").contains("error_code")
                .contains("trace_id").contains("created_at");
        // 协议与状态统一大写以匹配落库形态
        assertThat(w.getParamNameValuePairs().values()).contains("OPENAI", "SUCCESS", "alice", "team-a");
        // 未显式 select → 查询全部列
        assertThat(w.getSqlSelect()).isNull();
    }

    @Test
    void queryToleratesNullQuery() {
        Page<AuditLogEntity> page = new Page<>(1, 20);
        page.setRecords(List.of());
        doReturn(page).when(auditLogService).page(any(), any());

        AuditPage result = service.query(null).block(TIMEOUT);
        assertThat(result).isNotNull();
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(20);
    }

    @Test
    void usageMapsAggregatedRows() {
        List<Map<String, Object>> rows = List.of(
                Map.of("stat_key", "alice", "calls", 3L,
                        "prompt_tokens_sum", 30L, "completion_tokens_sum", 15L, "total_tokens_sum", 45L),
                Map.of("stat_key", "bob", "calls", 1L,
                        "prompt_tokens_sum", 5L, "completion_tokens_sum", 2L, "total_tokens_sum", 7L));
        doReturn(rows).when(auditLogService).listMaps(any(Wrapper.class));

        List<TokenUsageStat> stats = service.usage(UsageDimension.CALLER, null).block(TIMEOUT);

        assertThat(stats).hasSize(2);
        assertThat(stats.get(0)).isEqualTo(new TokenUsageStat("alice", 3L, 30L, 15L, 45L));
        assertThat(stats.get(1)).isEqualTo(new TokenUsageStat("bob", 1L, 5L, 2L, 7L));
    }

    @Test
    void usageIsCaseInsensitiveOnColumnLabels() {
        // 某些 DB 对未加引号的别名返回大写标签
        List<Map<String, Object>> rows = List.of(
                Map.of("STAT_KEY", "2026-09-10", "CALLS", 2,
                        "PROMPT_TOKENS_SUM", 20, "COMPLETION_TOKENS_SUM", 10, "TOTAL_TOKENS_SUM", 30));
        doReturn(rows).when(auditLogService).listMaps(any(Wrapper.class));

        List<TokenUsageStat> stats = service.usage(UsageDimension.DAY, null).block(TIMEOUT);

        assertThat(stats).containsExactly(new TokenUsageStat("2026-09-10", 2L, 20L, 10L, 30L));
    }

    @Test
    void usageReturnsEmptyWhenNoRows() {
        doReturn(List.of()).when(auditLogService).listMaps(any(Wrapper.class));
        assertThat(service.usage(UsageDimension.MODEL, null).block(TIMEOUT)).isEmpty();

        doReturn(null).when(auditLogService).listMaps(any(Wrapper.class));
        assertThat(service.usage(UsageDimension.MODEL, null).block(TIMEOUT)).isEmpty();
    }

    @Test
    void usageSelectsDimensionExpressionAndAggregates() {
        doReturn(List.of()).when(auditLogService).listMaps(any(Wrapper.class));

        service.usage(UsageDimension.CALLER, null).block(TIMEOUT);
        ArgumentCaptor<QueryWrapper<AuditLogEntity>> captor = wrapperCaptor();
        verify(auditLogService).listMaps(captor.capture());
        String select = captor.getValue().getSqlSelect();
        assertThat(select).contains("caller_id AS stat_key")
                .contains("COUNT(*) AS calls")
                .contains("SUM(total_tokens)")
                .contains("AS total_tokens_sum");
        assertThat(captor.getValue().getSqlSegment()).contains("GROUP BY").contains("caller_id");
    }

    @Test
    void usageDefaultsToDayDimensionWhenNull() {
        doReturn(List.of()).when(auditLogService).listMaps(any(Wrapper.class));

        service.usage(null, null).block(TIMEOUT);
        ArgumentCaptor<QueryWrapper<AuditLogEntity>> captor = wrapperCaptor();
        verify(auditLogService).listMaps(captor.capture());
        assertThat(captor.getValue().getSqlSelect()).contains("DATE(created_at) AS stat_key");
    }

    @Test
    void usageAppliesFilters() {
        doReturn(List.of()).when(auditLogService).listMaps(any(Wrapper.class));

        service.usage(UsageDimension.MODEL, new AuditQuery("alice", null, "glm-4", null, "SUCCESS",
                null, null, null, null, null, null, null, null)).block(TIMEOUT);

        ArgumentCaptor<QueryWrapper<AuditLogEntity>> captor = wrapperCaptor();
        verify(auditLogService).listMaps(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("caller_id").contains("logical_model");
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains("alice", "glm-4", "SUCCESS");
    }

    @Test
    void queryIsNonBlockingAndCompletes() {
        Page<AuditLogEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(entity(1, "alice", "deepseek-chat")));
        doReturn(page).when(auditLogService).page(any(), any());

        Mono<AuditPage> mono = service.query(AuditQuery.empty());
        assertThat(mono.block(TIMEOUT).items()).hasSize(1);
    }
}
