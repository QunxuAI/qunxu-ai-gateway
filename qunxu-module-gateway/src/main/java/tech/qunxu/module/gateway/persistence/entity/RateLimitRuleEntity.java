package tech.qunxu.module.gateway.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 限流配额规则实体，对应表 {@code rate_limit_rule}。
 *
 * <p>规则按 {@code ruleType} 区分维度（调用人默认/覆盖、租户覆盖、模型默认/覆盖、配额默认/覆盖），
 * 由 {@code RateLimitRuleService} 载入内存快照供请求热路径解析；全局开关仍在 yaml 配置。</p>
 */
@TableName("rate_limit_rule")
public class RateLimitRuleEntity extends BaseConfigEntity {

    /** 规则展示名。 */
    private String name;
    /** 规则类型：CALLER_DEFAULT/CALLER_OVERRIDE/TENANT_OVERRIDE/MODEL_DEFAULT/MODEL_OVERRIDE/QUOTA_DEFAULT/QUOTA_CALLER/QUOTA_TENANT。 */
    private String ruleType;
    /** 限流对象（caller/tenant/model 标识）；默认规则为 null。 */
    private String target;
    /** 每分钟许可；&lt;=0 不限制。 */
    private Integer permitsPerMinute;
    /** 每日 Token 上限；仅配额规则有值，&lt;=0 不限。 */
    private Long dailyTokens;
    /** 1 启用 0 停用。 */
    private Integer enabled;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRuleType() { return ruleType; }
    public void setRuleType(String ruleType) { this.ruleType = ruleType; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public Integer getPermitsPerMinute() { return permitsPerMinute; }
    public void setPermitsPerMinute(Integer permitsPerMinute) { this.permitsPerMinute = permitsPerMinute; }
    public Long getDailyTokens() { return dailyTokens; }
    public void setDailyTokens(Long dailyTokens) { this.dailyTokens = dailyTokens; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
}
