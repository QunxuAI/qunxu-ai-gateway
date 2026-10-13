package tech.qunxu.framework.security.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 安全配置项。
 * <p>
 * 通过 {@code qunxu.security.permit-all-urls} 配置无需登录即可访问的 URL。
 */
@ConfigurationProperties(prefix = "qunxu.security")
@Data
public class SecurityProperties {

    /**
     * 免登录的 URL 列表
     */
    private List<String> permitAllUrls = new ArrayList<>();

}
