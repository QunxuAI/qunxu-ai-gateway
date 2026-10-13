package tech.qunxu.module.system.dal.dataobject.oauth2;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tech.qunxu.framework.mybatis.core.dataobject.BaseDO;

/**
 * OAuth2 客户端 DO。
 * <p>
 * 全局表，不做租户隔离。登录、刷新令牌等接口需要携带合法客户端信息。
 */
@TableName("system_oauth2_client")
@Data
@EqualsAndHashCode(callSuper = true)
public class OAuth2ClientDO extends BaseDO {

    /** 客户端编号 */
    private String clientId;

    /** 客户端密钥 */
    private String secret;

    /** 客户端名称 */
    private String name;

    /** 客户端图标 */
    private String logo;

    /** 客户端描述 */
    private String description;

    /** 状态：0 关闭，1 开启 */
    private Integer status;

    /** 访问令牌有效期（秒） */
    private Integer accessTokenValiditySeconds;

    /** 刷新令牌有效期（秒） */
    private Integer refreshTokenValiditySeconds;

    /** 可用的授权码重定向地址（JSON 数组字符串） */
    private String redirectUris;

    /** 授权的权限范围（JSON 数组字符串） */
    private String scopes;

}
