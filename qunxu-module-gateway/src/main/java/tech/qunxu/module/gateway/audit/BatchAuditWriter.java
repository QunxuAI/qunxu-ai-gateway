package tech.qunxu.module.gateway.audit;

import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.persistence.entity.AuditLogEntity;
import tech.qunxu.module.gateway.persistence.service.AuditLogService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 批量审计写入器：以 Reactor {@link Sinks.Many} 作为内存队列，按 {@code gateway.audit.batch-size} 条或
 * {@code flush-interval-ms} 毫秒成批，经 {@link Schedulers#boundedElastic()} 用 MyBatis-Plus {@code saveBatch}
 * （JDBC 批处理）落库，避免每条审计都触发一次 DB 往返。
 *
 * <p>提交为「尽力而为、绝不阻塞主链」：队列有界（{@code queue-capacity}），满时丢弃并计数；DB 写入失败仅告警。
 * 关闭时 {@code tryEmitComplete} 触发末批冲刷，并等待落库完成（最多 5s）以保证优雅退出。</p>
 */
@Component
public class BatchAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(BatchAuditWriter.class);
    private static final long SHUTDOWN_AWAIT_SECONDS = 5L;

    private final AuditLogService auditLogService;
    private final int batchSize;
    private final long flushIntervalMs;
    private final Sinks.Many<AuditRecord> sink;
    private final CountDownLatch drained = new CountDownLatch(1);
    private final AtomicLong dropped = new AtomicLong();

    private volatile Disposable subscription;

    public BatchAuditWriter(AuditLogService auditLogService, GatewayProperties properties) {
        this.auditLogService = auditLogService;
        GatewayProperties.Audit audit = properties.getAudit();
        this.batchSize = Math.max(1, audit.getBatchSize());
        this.flushIntervalMs = Math.max(50L, audit.getFlushIntervalMs());
        int capacity = Math.max(this.batchSize, audit.getQueueCapacity());
        this.sink = Sinks.many().unicast().onBackpressureBuffer(new ArrayBlockingQueue<>(capacity));
    }

    @PostConstruct
    public void start() {
        this.subscription = sink.asFlux()
                .bufferTimeout(batchSize, Duration.ofMillis(flushIntervalMs))
                .filter(batch -> !batch.isEmpty())
                .concatMap(this::writeBatch)
                .subscribe(
                        ignored -> {
                        },
                        e -> {
                            log.error("审计批量写入流异常终止", e);
                            drained.countDown();
                        },
                        drained::countDown);
        log.info("审计批量写入器已启动: batchSize={} flushIntervalMs={}", batchSize, flushIntervalMs);
    }

    @PreDestroy
    public void stop() {
        sink.tryEmitComplete();
        try {
            if (!drained.await(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("审计批量写入优雅关闭超时（{}s），可能存在未落库记录", SHUTDOWN_AWAIT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (subscription != null) {
                subscription.dispose();
            }
        }
    }

    /** 提交一条审计记录（非阻塞）。队列满或已终止时丢弃并计数，绝不抛出影响主链。 */
    public void submit(AuditRecord record) {
        if (record == null) {
            return;
        }
        Sinks.EmitResult result = sink.tryEmitNext(record);
        if (result.isFailure()) {
            long total = dropped.incrementAndGet();
            if (log.isDebugEnabled()) {
                log.debug("审计入队失败({})，累计丢弃 {} 条", result, total);
            }
        }
    }

    /** 累计因队列满/终止而丢弃的审计条数（供可观测性）。 */
    public long droppedCount() {
        return dropped.get();
    }

    private Mono<Void> writeBatch(List<AuditRecord> batch) {
        return Mono.<Void>fromRunnable(() -> {
                    List<AuditLogEntity> entities = batch.stream().map(BatchAuditWriter::toEntity).toList();
                    auditLogService.saveBatch(entities);
                    if (log.isDebugEnabled()) {
                        log.debug("审计批量落库 {} 条", entities.size());
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("审计批量落库失败（{} 条）: {}", batch.size(), e.toString());
                    return Mono.empty();
                });
    }

    private static AuditLogEntity toEntity(AuditRecord r) {
        AuditLogEntity e = new AuditLogEntity();
        e.setTraceId(r.traceId());
        e.setCallerId(r.callerId());
        e.setCallerName(r.callerName());
        e.setTenant(r.tenant());
        e.setClientIp(r.clientIp());
        e.setProtocol(r.protocol());
        e.setEndpoint(r.endpoint());
        e.setLogicalModel(r.logicalModel());
        e.setChannelId(r.channelId());
        e.setProviderId(r.providerId());
        e.setProviderCode(r.providerCode());
        e.setUpstreamModel(r.upstreamModel());
        e.setRequestBody(r.requestBody());
        e.setResponseBody(r.responseBody());
        e.setPromptTokens(r.promptTokens());
        e.setCompletionTokens(r.completionTokens());
        e.setTotalTokens(r.totalTokens());
        e.setLatencyMs(r.latencyMs());
        e.setFirstTokenMs(r.firstTokenMs());
        e.setStatus(r.status() != null ? r.status().name() : null);
        e.setErrorCode(r.errorCode());
        e.setErrorMsg(r.errorMsg());
        e.setGuardrailHit(r.guardrailHit());
        e.setModerationResult(r.moderationResult());
        e.setCreatedAt(r.createdAt());
        return e;
    }
}
