package tech.qunxu.module.gateway.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 通道健康事件实体，对应表 {@code health_event}。追加型表，无逻辑删除与乐观锁。
 * 记录剔除/恢复/探测失败/凭证过期等健康状态变迁，供 Admin 与告警查询。
 */
@TableName("health_event")
public class HealthEventEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long channelId;
    private Long providerId;
    /** 事件类型: EJECT / RECOVER / PROBE_FAIL / AUTH_EXPIRED。 */
    private String eventType;
    /** 变迁前状态，取值见 {@link tech.qunxu.module.gateway.common.enums.HealthStatus}。 */
    private String fromStatus;
    /** 变迁后状态。 */
    private String toStatus;
    private String reason;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getChannelId() { return channelId; }
    public void setChannelId(Long channelId) { this.channelId = channelId; }
    public Long getProviderId() { return providerId; }
    public void setProviderId(Long providerId) { this.providerId = providerId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getFromStatus() { return fromStatus; }
    public void setFromStatus(String fromStatus) { this.fromStatus = fromStatus; }
    public String getToStatus() { return toStatus; }
    public void setToStatus(String toStatus) { this.toStatus = toStatus; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
