package tech.qunxu.framework.tenant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Collections;
import java.util.Set;

/**
 * 多租户配置项。
 * <p>
 * 通过 {@code qunxu.tenant.*} 配置多租户功能：
 * <ul>
 *     <li>{@code enable}：是否开启多租户，默认开启；</li>
 *     <li>{@code ignore-tables}：需要忽略多租户的表（默认通过实体类继承关系自动判断）；</li>
 *     <li>{@code ignore-urls}：无需携带 tenant-id 请求头的 URL。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "qunxu.tenant")
@Data
public class TenantProperties {

    /**
     * 是否开启多租户，默认开启
     */
    private Boolean enable = true;

    /**
     * 需要忽略多租户的表
     */
    private Set<String> ignoreTables = Collections.emptySet();

    /**
     * 需要忽略多租户的请求 URL（无需携带 tenant-id 请求头）
     */
    private Set<String> ignoreUrls = Collections.emptySet();

}
