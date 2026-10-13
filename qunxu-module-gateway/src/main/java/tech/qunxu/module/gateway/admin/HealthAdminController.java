package tech.qunxu.module.gateway.admin;

import tech.qunxu.module.gateway.admin.service.HealthAdminService;
import tech.qunxu.module.gateway.health.ActiveProbeScheduler;
import tech.qunxu.module.gateway.persistence.entity.HealthEventEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通道健康管理接口（{@code /admin/health}）：健康视图、手动探测/剔除/恢复、健康事件时间线。
 *
 * <p>手动干预与自动自愈共用同一套健康存储与事件表，因此人工摘流/恢复同样可在
 * {@code GET /admin/health/events} 中追溯，并计入 {@code gateway.channel.health.events} 指标。</p>
 */
@RestController
@RequestMapping("/admin-api/gateway/health")
public class HealthAdminController {

    private final HealthAdminService healthAdminService;
    private final ActiveProbeScheduler probeScheduler;

    public HealthAdminController(HealthAdminService healthAdminService, ActiveProbeScheduler probeScheduler) {
        this.healthAdminService = healthAdminService;
        this.probeScheduler = probeScheduler;
    }

    /** 全量通道健康汇总（各状态计数 + 可路由数），适合做管理首页概览与告警看板。 */
    @GetMapping("/summary")
    public Mono<HealthAdminService.HealthSummary> summary() {
        return healthAdminService.summary();
    }

    /** 通道健康视图分页：静态配置 + 运行时健康合并呈现（先过滤后分页）。 */
    @GetMapping("/channels")
    public Mono<AdminPage<ChannelHealthView>> channels(
            @RequestParam(required = false) Long logicalModelId,
            @RequestParam(required = false) Long providerId,
            @RequestParam(required = false) Boolean onlyUnhealthy,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return healthAdminService.channelsPage(logicalModelId, providerId, onlyUnhealthy, page, size);
    }

    /** 单个通道的健康视图。 */
    @GetMapping("/channels/{id}")
    public Mono<ChannelHealthView> channel(@PathVariable Long id) {
        return healthAdminService.channel(id);
    }

    /**
     * 手动探测一个通道：无论当前状态如何都发起一次真实探测，结果照常回写健康并触发自动恢复/剔除判定。
     * 常用于「新配置的通道上线前验证凭证与连通性」或「排查某通道为何被剔除」。
     */
    @PostMapping("/channels/{id}/probe")
    public Mono<HealthAdminService.ProbeOutcome> probe(@PathVariable Long id) {
        return healthAdminService.probe(id);
    }

    /** 手动恢复通道：置为 UP、清除冷却与连续失败计数，并记 RECOVER 事件。 */
    @PostMapping("/channels/{id}/recover")
    public Mono<ChannelHealthView> recover(@PathVariable Long id) {
        return healthAdminService.recover(id);
    }

    /**
     * 手动剔除通道：置为 EJECTED 并进入冷却期，记 EJECT 事件。
     *
     * @param cooldownSeconds 冷却时长（秒），缺省取 {@code gateway.health.eject-cooldown-ms}
     */
    @PostMapping("/channels/{id}/eject")
    public Mono<ChannelHealthView> eject(@PathVariable Long id,
                                         @RequestParam(required = false) Long cooldownSeconds) {
        return healthAdminService.eject(id, cooldownSeconds);
    }

    /**
     * 手动触发一轮全量主动探测（走与定时任务相同的路径，含分布式锁）。
     *
     * @return 本轮实际发起探测的通道数；未抢到锁时为 0
     */
    @PostMapping("/probe-round")
    public Mono<Integer> probeRound() {
        return probeScheduler.probeRound();
    }

    /** 分页查询健康事件（按创建时间倒序），用于追溯剔除/恢复/凭证过期时间线。 */
    @GetMapping("/events")
    public Mono<AdminPage<HealthEventEntity>> events(
            @RequestParam(required = false) Long channelId,
            @RequestParam(required = false) Long providerId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return healthAdminService.events(channelId, providerId, eventType, from, to, page, size);
    }
}
