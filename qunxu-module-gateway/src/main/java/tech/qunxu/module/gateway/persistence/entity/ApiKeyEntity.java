package tech.qunxu.module.gateway.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 网关 APIKey 实体，对应表 {@code gateway_api_key}。
 *
 * <p>APIKey 是绑定到系统用户（{@code system_users.id}）的数据面调用凭证：客户端携带
 * {@code Authorization: Bearer <key>} 调用 {@code /v1/**}、{@code /anthropic/**}，
 * 由 {@code ApiKeySnapshotService} 按 SHA-256 摘要匹配后映射为调用人身份。</p>
 *
 * <p>安全约定：本实体<b>只存密钥摘要与展示前缀，永不落库明文</b>；明文仅在创建/轮换接口的
 * 响应中一次性返回。管理端权限：管理员（super_admin）可管理所有用户的 APIKey，
 * 普通用户只能管理自己的（{@code userId} 与当前登录用户一致）。</p>
 */
@TableName("gateway_api_key")
public class ApiKeyEntity extends BaseConfigEntity {

    /** 所属用户 ID（system_users.id）。 */
    private Long userId;

    /** 所属用户名快照（创建时记录，供管理端展示，不随用户改名同步）。 */
    private String userName;

    /** APIKey 名称（调用方自定义，如「生产环境」）。 */
    private String name;

    /** APIKey 的 SHA-256 十六进制摘要（长度 64）。 */
    private String apiKeyHash;

    /** APIKey 展示前缀（如 {@code sk-qx-abc12345}，不回显完整密钥）。 */
    private String keyPrefix;

    /** 归属租户标识，未指定时为 {@code default}。 */
    private String tenant;

    /** 1 启用 0 停用。 */
    private Integer status;

    /** 过期时间，{@code null} 表示永不过期。 */
    private LocalDateTime expiresAt;

    /** 最近一次成功调用时间（由快照服务异步回写，展示用）。 */
    private LocalDateTime lastUsedAt;

    /** 备注。 */
    private String remark;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getApiKeyHash() { return apiKeyHash; }
    public void setApiKeyHash(String apiKeyHash) { this.apiKeyHash = apiKeyHash; }
    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
    public String getTenant() { return tenant; }
    public void setTenant(String tenant) { this.tenant = tenant; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
