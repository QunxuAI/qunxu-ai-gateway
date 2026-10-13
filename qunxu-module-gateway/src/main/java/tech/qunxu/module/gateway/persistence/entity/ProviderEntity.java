package tech.qunxu.module.gateway.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/**
 * 供应商实体，对应表 {@code provider}。
 */
@TableName("provider")
public class ProviderEntity extends BaseConfigEntity {

    private String name;
    /** 供应商编码，取值见 {@link tech.qunxu.module.gateway.common.enums.ProviderType}。 */
    private String code;
    private String baseUrl;
    /** 鉴权类型: BEARER / ZHIPU_JWT / QIANFAN_IAM / SPARK_WS。 */
    private String authType;
    /** 加密后的凭证 JSON（apiKey/apiSecret/appId 等）。 */
    private String credentials;
    /** 1 启用 0 禁用。 */
    private Integer status;
    private Integer priority;
    private String remark;

    @Version
    private Integer version;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }
    public String getCredentials() { return credentials; }
    public void setCredentials(String credentials) { this.credentials = credentials; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
