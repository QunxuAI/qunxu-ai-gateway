package tech.qunxu.module.gateway.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc-openapi 文档配置。定义 Bearer(JWT) 安全方案，便于在 Swagger UI 中调试受保护接口。
 *
 * <p>按使用人群拆成两个分组，避免管理接口与对外推理接口混在同一份文档里：</p>
 * <ul>
 *   <li>{@code gateway}（{@code /v1/**}）：对客户端暴露的 OpenAI / Anthropic 兼容推理与多模态端点；</li>
 *   <li>{@code admin}（{@code /admin/**}）：供应商/通道/模型/策略/敏感词 CRUD、审计查询、健康视图与缓存管理。</li>
 * </ul>
 * <p>分组文档地址为 {@code /v3/api-docs/gateway} 与 {@code /v3/api-docs/admin}，
 * Swagger UI（{@code /swagger-ui.html}）右上角可切换。</p>
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI gatewayOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Qunxu Model Gateway API")
                        .version("v1")
                        .description("响应式 AI 模型网关：上游 OpenAI / Anthropic 兼容协议，下游适配国内大模型厂商。")
                        .license(new License().name("Proprietary")))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    /** 对外推理端点分组（OpenAI / Anthropic 兼容协议）。 */
    @Bean
    public GroupedOpenApi gatewayApi() {
        return GroupedOpenApi.builder()
                .group("gateway")
                .displayName("推理端点（OpenAI / Anthropic 兼容）")
                .pathsToMatch("/v1/**")
                .build();
    }

    /** 管理端点分组（配置 CRUD、审计、健康、缓存）。 */
    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder()
                .group("admin")
                .displayName("管理端点（/admin/**）")
                .pathsToMatch("/admin-api/gateway/**")
                .build();
    }
}
