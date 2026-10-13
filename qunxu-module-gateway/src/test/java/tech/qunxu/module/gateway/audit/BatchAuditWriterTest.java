package tech.qunxu.module.gateway.audit;

import tech.qunxu.module.gateway.common.enums.CallStatus;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.persistence.entity.AuditLogEntity;
import tech.qunxu.module.gateway.persistence.service.AuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * {@link BatchAuditWriter} 单元测试：mock {@link AuditLogService}，验证「按条数成批」「按时间窗冲刷」
 * 「记录→实体字段映射」「关闭时优雅排空末批」「队列满丢弃计数」与「null 记录忽略」。
 *
 * <p>批量落库在 {@code boundedElastic} 异步执行，故用 Mockito {@code timeout(...)} 等待调用发生，避免脆弱睡眠。</p>
 */
class BatchAuditWriterTest {

    private AuditLogService auditLogService;
    private BatchAuditWriter writer;

    @BeforeEach
    void setUp() {
        auditLogService = mock(AuditLogService.class);
    }

    @AfterEach
    void tearDown() {
        if (writer != null) {
            writer.stop();
            writer = null;
        }
    }

    /** 构建并启动一个写入器（赋值到字段以便 {@link #tearDown()} 优雅关闭）。 */
    private BatchAuditWriter newWriter(int batchSize, long flushIntervalMs, int queueCapacity) {
        GatewayProperties props = new GatewayProperties();
        props.getAudit().setBatchSize(batchSize);
        props.getAudit().setFlushIntervalMs(flushIntervalMs);
        props.getAudit().setQueueCapacity(queueCapacity);
        BatchAuditWriter w = new BatchAuditWriter(auditLogService, props);
        w.start();
        this.writer = w;
        return w;
    }

    private static AuditRecord sampleRecord(String traceId) {
        return new AuditRecord(traceId, "alice", "Alice", "team-a", "10.0.0.1",
                "OPENAI", "chat.completions", "deepseek-chat", 10L, 100L, "DEEPSEEK", "deepseek-chat",
                "{\"req\":1}", "{\"resp\":1}", 10, 5, 15, 123L, 45L,
                CallStatus.SUCCESS, null, null, null, null,
                LocalDateTime.of(2026, 9, 10, 12, 0, 0));
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Collection<AuditLogEntity>> entityCaptor() {
        return ArgumentCaptor.forClass(Collection.class);
    }

    @Test
    void flushesWhenBatchSizeReached() {
        newWriter(3, 5000, 100);

        writer.submit(sampleRecord("t1"));
        writer.submit(sampleRecord("t2"));
        // 前两条不足批量，不应触发落库
        verify(auditLogService, never()).saveBatch(any());

        writer.submit(sampleRecord("t3"));   // 第 3 条触发按条数成批
        ArgumentCaptor<Collection<AuditLogEntity>> captor = entityCaptor();
        verify(auditLogService, timeout(3000)).saveBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(3);
    }

    @Test
    void flushesOnIntervalWhenBelowBatchSize() {
        newWriter(100, 150, 1000);

        writer.submit(sampleRecord("t1"));
        writer.submit(sampleRecord("t2"));

        ArgumentCaptor<Collection<AuditLogEntity>> captor = entityCaptor();
        verify(auditLogService, timeout(3000)).saveBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    void mapsRecordFieldsToEntity() {
        newWriter(1, 1000, 100);

        writer.submit(sampleRecord("trace-xyz"));

        ArgumentCaptor<Collection<AuditLogEntity>> captor = entityCaptor();
        verify(auditLogService, timeout(3000)).saveBatch(captor.capture());
        AuditLogEntity e = captor.getValue().iterator().next();

        assertThat(e.getTraceId()).isEqualTo("trace-xyz");
        assertThat(e.getCallerId()).isEqualTo("alice");
        assertThat(e.getCallerName()).isEqualTo("Alice");
        assertThat(e.getTenant()).isEqualTo("team-a");
        assertThat(e.getProtocol()).isEqualTo("OPENAI");
        assertThat(e.getLogicalModel()).isEqualTo("deepseek-chat");
        assertThat(e.getChannelId()).isEqualTo(10L);
        assertThat(e.getProviderId()).isEqualTo(100L);
        assertThat(e.getProviderCode()).isEqualTo("DEEPSEEK");
        assertThat(e.getPromptTokens()).isEqualTo(10);
        assertThat(e.getCompletionTokens()).isEqualTo(5);
        assertThat(e.getTotalTokens()).isEqualTo(15);
        assertThat(e.getLatencyMs()).isEqualTo(123L);
        assertThat(e.getFirstTokenMs()).isEqualTo(45L);
        assertThat(e.getStatus()).isEqualTo("SUCCESS");
        // createdAt 由记录携带（调用结束时刻），显式落库、不被自动填充覆盖
        assertThat(e.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 9, 10, 12, 0, 0));
    }

    @Test
    void gracefulShutdownFlushesPendingBatch() {
        BatchAuditWriter w = newWriter(100, 60_000, 1000);   // 时间窗远大于测试时长，仅靠关闭冲刷
        w.submit(sampleRecord("t1"));
        w.submit(sampleRecord("t2"));

        w.stop();   // tryEmitComplete 触发末批冲刷并等待落库
        writer = null;   // 已手动关闭，避免 tearDown 二次 stop

        ArgumentCaptor<Collection<AuditLogEntity>> captor = entityCaptor();
        verify(auditLogService).saveBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    void ignoresNullRecord() {
        newWriter(1, 1000, 100);
        writer.submit(null);
        verify(auditLogService, never()).saveBatch(any());
        assertThat(writer.droppedCount()).isZero();
    }

    @Test
    void countsDroppedWhenQueueFull() {
        // 不启动订阅者：unicast sink 在有界队列中缓冲，队列满后 tryEmitNext 失败并计数
        GatewayProperties props = new GatewayProperties();
        props.getAudit().setBatchSize(1);
        props.getAudit().setQueueCapacity(1);
        BatchAuditWriter unstarted = new BatchAuditWriter(auditLogService, props);

        unstarted.submit(sampleRecord("t1"));   // 入队（容量 1）
        unstarted.submit(sampleRecord("t2"));   // 队满，丢弃

        assertThat(unstarted.droppedCount()).isEqualTo(1L);
        verify(auditLogService, never()).saveBatch(any());
    }

    @Test
    void writeFailureDoesNotPropagateAndStreamSurvives() {
        // saveBatch 抛异常时应被 onErrorResume 吞掉，写入流不终止，后续批次仍可落库
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .doReturn(true)
                .when(auditLogService).saveBatch(any());
        newWriter(1, 100, 1000);

        writer.submit(sampleRecord("t1"));   // 触发异常批
        verify(auditLogService, timeout(3000)).saveBatch(any());

        writer.submit(sampleRecord("t2"));   // 流仍存活，后续批正常
        verify(auditLogService, timeout(3000).times(2)).saveBatch(any());
    }

    @Test
    void entityListIsPassedAsBatch() {
        newWriter(2, 5000, 100);
        writer.submit(sampleRecord("t1"));
        writer.submit(sampleRecord("t2"));

        ArgumentCaptor<Collection<AuditLogEntity>> captor = entityCaptor();
        verify(auditLogService, timeout(3000)).saveBatch(captor.capture());
        List<AuditLogEntity> batch = List.copyOf(captor.getValue());
        assertThat(batch).extracting(AuditLogEntity::getTraceId).containsExactly("t1", "t2");
    }
}
