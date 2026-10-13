package tech.qunxu.module.gateway.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 对外统一逻辑模型实体，对应表 {@code logical_model}。
 */
@TableName("logical_model")
public class LogicalModelEntity extends BaseConfigEntity {

    /** 对外统一模型名，客户端请求中的 model 字段。 */
    private String name;
    /** 能力，取值见 {@link tech.qunxu.module.gateway.common.enums.Capability}。 */
    private String capability;
    private String description;
    /** 1 启用 0 禁用。 */
    private Integer status;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCapability() { return capability; }
    public void setCapability(String capability) { this.capability = capability; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
