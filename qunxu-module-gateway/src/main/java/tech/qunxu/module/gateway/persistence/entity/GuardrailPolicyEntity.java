package tech.qunxu.module.gateway.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 安全围栏策略实体，对应表 {@code guardrail_policy}。
 * 策略按 scope（全局/租户/模型）绑定，命中后按 action 决定拦截/脱敏/告警。
 */
@TableName("guardrail_policy")
public class GuardrailPolicyEntity extends BaseConfigEntity {

    private String name;
    /** 作用域: GLOBAL / TENANT / MODEL。 */
    private String scope;
    /** scope 为 TENANT/MODEL 时的具体取值。 */
    private String scopeValue;
    /** 规则集合 JSON。 */
    private String rulesJson;
    /** 命中动作: BLOCK / MASK / WARN。 */
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
    public String getRulesJson() { return rulesJson; }
    public void setRulesJson(String rulesJson) { this.rulesJson = rulesJson; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
}
