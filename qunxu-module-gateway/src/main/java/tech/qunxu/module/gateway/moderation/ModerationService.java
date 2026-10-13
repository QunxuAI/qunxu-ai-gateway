package tech.qunxu.module.gateway.moderation;

import tech.qunxu.module.gateway.common.enums.Capability;
import tech.qunxu.module.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内容审计编排服务：按策略选定 {@link ModerationProvider}，对文本执行审计，并结合策略 {@code action}
 * 将提供方的<b>原始风险裁决</b>转换为<b>最终执行裁决</b>（PASS/REVIEW/BLOCK），供流水线过滤器据此放行/标记/拦截。
 *
 * <p>执行语义：原始 PASS 恒放行；命中（REVIEW/BLOCK）时按策略动作处置——{@code PASS} 仅记录、{@code REVIEW} 标记复核放行、
 * {@code BLOCK} 对明确违规拦截、对疑似降级为复核。提供方缺失或异常时以 PASS 兜底（失败开放），绝不阻断主链。</p>
 */
@Service
public class ModerationService {

    private static final Logger log = LoggerFactory.getLogger(ModerationService.class);

    private final Map<String, ModerationProvider> providers = new HashMap<>();
    private final ModerationPolicyService policyService;
    private final GatewayProperties properties;
    private final String defaultProvider;

    public ModerationService(List<ModerationProvider> providerList,
                             ModerationPolicyService policyService,
                             GatewayProperties properties) {
        if (providerList != null) {
            for (ModerationProvider p : providerList) {
                if (p != null && p.name() != null) {
                    providers.put(p.name(), p);
                }
            }
        }
        this.policyService = policyService;
        this.properties = properties;
        this.defaultProvider = properties.getModeration().getDefaultProvider();
        if (log.isInfoEnabled()) {
            log.info("Moderation service initialized with provider(s): {}", providers.keySet());
        }
    }

    public boolean isEnabled() {
        return properties.getModeration().isEnabled();
    }

    /**
     * 对文本执行内容审计。
     *
     * @param text         待审文本
     * @param direction    方向标识（"INPUT"/"OUTPUT"，供远程提供方参考）
     * @param capability   能力
     * @param logicalModel 逻辑模型名（MODEL 作用域匹配）
     * @param tenant       租户（TENANT 作用域匹配）
     * @return 最终执行裁决；禁用或空文本时为 PASS
     */
    public Mono<ModerationResult> moderate(String text, String direction, Capability capability,
                                           String logicalModel, String tenant) {
        if (!isEnabled() || text == null || text.isEmpty()) {
            return Mono.just(ModerationResult.pass("none"));
        }
        return policyService.resolve(tenant, logicalModel)
                .flatMap(resolved -> {
                    ModerationProvider provider = providers.get(resolved.provider());
                    if (provider == null) {
                        provider = providers.get(defaultProvider);
                    }
                    if (provider == null) {
                        log.debug("无可用内容审计提供方({})，放行", resolved.provider());
                        return Mono.just(ModerationResult.pass("none"));
                    }
                    ModerationContext ctx = new ModerationContext(
                            text, direction, capability, logicalModel, tenant, resolved.config());
                    ModerationProvider chosen = provider;
                    // defer 包裹：提供方在装配期同步抛错也转为 onError，统一走失败开放
                    return Mono.defer(() -> chosen.moderate(text, ctx))
                            .map(raw -> enforce(raw, resolved.action()))
                            // 提供方返回空信号时同样失败开放，避免流水线因缺失裁决而静默中断
                            .switchIfEmpty(Mono.fromSupplier(() -> ModerationResult.pass(chosen.name())))
                            .onErrorResume(e -> {
                                log.warn("内容审计执行异常，失败开放放行: {}", e.toString());
                                return Mono.just(ModerationResult.of(ModerationDecision.PASS, List.of(),
                                        "moderation-error: " + e.getClass().getSimpleName(), chosen.name()));
                            });
                });
    }

    /** 结合策略动作，把原始风险裁决转换为最终执行裁决。 */
    private ModerationResult enforce(ModerationResult raw, String action) {
        if (raw == null) {
            return ModerationResult.pass("none");
        }
        if (raw.decision() == ModerationDecision.PASS) {
            return raw;
        }
        String act = action == null ? "BLOCK" : action.trim().toUpperCase();
        ModerationDecision finalDecision = switch (act) {
            case "PASS" -> ModerationDecision.PASS;
            case "REVIEW" -> ModerationDecision.REVIEW;
            default -> raw.decision() == ModerationDecision.BLOCK
                    ? ModerationDecision.BLOCK : ModerationDecision.REVIEW;
        };
        return new ModerationResult(finalDecision, raw.categories(), raw.detail(), raw.provider());
    }
}
