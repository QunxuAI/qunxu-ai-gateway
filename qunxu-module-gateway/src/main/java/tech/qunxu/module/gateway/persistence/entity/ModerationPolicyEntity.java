package tech.qunxu.module.gateway.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 内容审计策略实体，对应表 {@code moderation_policy}。
 * provider 指定审计实现（builtin/aliyun/llm），action 决定拦截/复核/放行。
 */
@TableName("moderation_policy")
public class ModerationPolicyEntity extends BaseConfigEntity {

    private String name;
    /** 作用域: GLOBAL / TENANT / MODEL。 */
    private String scope;
    private String scopeValue;
    /** 审计提供方: builtin / aliyun / llm。 */
    private String provider;
    /** 提供方专属配置 JSON。 */
    private String configJson;
    /** 命中动作: BLOCK / REVIEW / PASS。 */
    private String action;
    /** 1 启用 0 停用。 */
    private Integer enabled;
    private Integer priority;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getScopeValue() { return scopeValue; }
    public void setScopeValue(String scopeValue) { this.scopeValue = scopeValue; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
}
