package tech.qunxu.module.gateway.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/**
 * 模型通道实体，对应表 {@code model_channel}。
 * 表示「某个逻辑模型由某个供应商的某个真实模型来承载」，是负载均衡与健康管理的基本单位。
 */
@TableName("model_channel")
public class ModelChannelEntity extends BaseConfigEntity {

    private Long logicalModelId;
    private Long providerId;
    /** 下游真实模型名，如 deepseek-chat、qwen-plus。 */
    private String upstreamModel;
    /** 通道协议，取值见 {@link tech.qunxu.module.gateway.common.enums.ChannelProtocol}。 */
    private String protocol;
    private Integer weight;
    private Integer priority;
    /** 1 启用 0 停用。 */
    private Integer enabled;
    /** 通道级额外配置 JSON（端点覆盖、特殊参数等）。 */
    private String configJson;

    @Version
    private Integer version;

    public Long getLogicalModelId() { return logicalModelId; }
    public void setLogicalModelId(Long logicalModelId) { this.logicalModelId = logicalModelId; }
    public Long getProviderId() { return providerId; }
    public void setProviderId(Long providerId) { this.providerId = providerId; }
    public String getUpstreamModel() { return upstreamModel; }
    public void setUpstreamModel(String upstreamModel) { this.upstreamModel = upstreamModel; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public Integer getWeight() { return weight; }
    public void setWeight(Integer weight) { this.weight = weight; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
