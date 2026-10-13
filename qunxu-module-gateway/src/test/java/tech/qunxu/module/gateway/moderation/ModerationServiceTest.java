package tech.qunxu.module.gateway.moderation;

import tech.qunxu.module.gateway.common.enums.Capability;
import tech.qunxu.module.gateway.config.GatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ModerationService} 单元测试（M7c）：验证提供方按策略选择与缺省回退、策略 {@code action} 对原始风险裁决的
 * <b>执行转换</b>（PASS/REVIEW/BLOCK）、上下文透传，以及禁用/空文本/提供方缺失/提供方异常时的<b>失败开放</b>语义。
 */
class ModerationServiceTest {

    private ModerationPolicyService policyService;
    private GatewayProperties properties;

    /** 可编程的假提供方：返回预置结果或抛出异常，并记录收到的上下文。 */
    private static final class FakeProvider implements ModerationProvider {
        private final String name;
        private final ModerationResult result;
        private final RuntimeException error;
        private ModerationContext lastContext;
        private int calls;

        FakeProvider(String name, ModerationResult result) {
            this(name, result, null);
        }

        FakeProvider(String name, ModerationResult result, RuntimeException error) {
            this.name = name;
            this.result = result;
            this.error = error;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Mono<ModerationResult> moderate(String text, ModerationContext context) {
            calls++;
            lastContext = context;
            if (error != null) {
                return Mono.error(error);
            }
            return Mono.just(result);
        }
    }

    @BeforeEach
    void setUp() {
        policyService = mock(ModerationPolicyService.class);
        properties = new GatewayProperties();
    }

    private ModerationService service(ModerationProvider... providers) {
        return new ModerationService(List.of(providers), policyService, properties);
    }

    private void resolveTo(String provider, String action) {
        when(policyService.resolve(any(), any())).thenReturn(Mono.just(
                new ResolvedModeration(provider, action, Map.of("k", "v"))));
    }

    private static ModerationResult raw(ModerationDecision decision, String... categories) {
        return ModerationResult.of(decision, List.of(categories), "raw-detail", "fake");
    }

    private ModerationResult moderate(ModerationService service, String text) {
        return service.moderate(text, "INPUT", Capability.CHAT, "deepseek-chat", "tenant-a")
                .block(Duration.ofSeconds(5));
    }

    @Test
    void disabledShortCircuitsToPass() {
        properties.getModeration().setEnabled(false);
        resolveTo("fake", "BLOCK");
        FakeProvider provider = new FakeProvider("fake", raw(ModerationDecision.BLOCK, "广告"));
        ModerationService service = service(provider);

        assertThat(service.isEnabled()).isFalse();
        ModerationResult result = moderate(service, "赌博");

        assertThat(result.decision()).isEqualTo(ModerationDecision.PASS);
        assertThat(result.provider()).isEqualTo("none");
        assertThat(provider.calls).isZero();
        verify(policyService, never()).resolve(any(), any());
    }

    @Test
    void blankTextShortCircuitsToPass() {
        resolveTo("fake", "BLOCK");
        FakeProvider provider = new FakeProvider("fake", raw(ModerationDecision.BLOCK));
        ModerationService service = service(provider);

        assertThat(moderate(service, "").decision()).isEqualTo(ModerationDecision.PASS);
        assertThat(moderate(service, null).decision()).isEqualTo(ModerationDecision.PASS);
        assertThat(provider.calls).isZero();
    }

    @Test
    void selectsProviderResolvedByPolicy() {
        resolveTo("secondary", "BLOCK");
        FakeProvider primary = new FakeProvider("builtin", raw(ModerationDecision.PASS));
        FakeProvider secondary = new FakeProvider("secondary", raw(ModerationDecision.BLOCK, "涉政"));

        ModerationResult result = moderate(service(primary, secondary), "违规内容");

        assertThat(primary.calls).isZero();
        assertThat(secondary.calls).isEqualTo(1);
        assertThat(result.decision()).isEqualTo(ModerationDecision.BLOCK);
    }

    @Test
    void fallsBackToDefaultProviderWhenResolvedIsUnknown() {
        properties.getModeration().setDefaultProvider("builtin");
        resolveTo("not-registered", "BLOCK");
        FakeProvider builtin = new FakeProvider("builtin", raw(ModerationDecision.REVIEW, "辱骂"));

        ModerationResult result = moderate(service(builtin), "可疑内容");

        assertThat(builtin.calls).isEqualTo(1);
        assertThat(result.decision()).isEqualTo(ModerationDecision.REVIEW);
    }

    @Test
    void passesWhenNoProviderAvailable() {
        resolveTo("builtin", "BLOCK");

        ModerationResult result = moderate(service(), "任意内容");

        assertThat(result.decision()).isEqualTo(ModerationDecision.PASS);
        assertThat(result.provider()).isEqualTo("none");
    }

    @Test
    void rawPassAlwaysPassesRegardlessOfAction() {
        resolveTo("fake", "BLOCK");
        FakeProvider provider = new FakeProvider("fake", raw(ModerationDecision.PASS));

        ModerationResult result = moderate(service(provider), "良性内容");

        assertThat(result.decision()).isEqualTo(ModerationDecision.PASS);
        assertThat(result.categories()).isEmpty();
    }

    @Test
    void actionPassDowngradesRiskToPass() {
        resolveTo("fake", "PASS");
        FakeProvider provider = new FakeProvider("fake", raw(ModerationDecision.BLOCK, "广告"));

        ModerationResult result = moderate(service(provider), "赌博");

        assertThat(result.decision()).isEqualTo(ModerationDecision.PASS);
        // 原始类别与说明保留，供审计观测
        assertThat(result.categories()).containsExactly("广告");
        assertThat(result.detail()).isEqualTo("raw-detail");
    }

    @Test
    void actionReviewDowngradesBlockToReview() {
        resolveTo("fake", "REVIEW");
        FakeProvider provider = new FakeProvider("fake", raw(ModerationDecision.BLOCK, "色情"));

        ModerationResult result = moderate(service(provider), "色情内容");

        assertThat(result.decision()).isEqualTo(ModerationDecision.REVIEW);
        assertThat(result.flagged()).isTrue();
        assertThat(result.blocked()).isFalse();
    }

    @Test
    void actionBlockKeepsBlockAndDowngradesReview() {
        FakeProvider blocker = new FakeProvider("fake", raw(ModerationDecision.BLOCK, "暴恐"));
        resolveTo("fake", "BLOCK");
        assertThat(moderate(service(blocker), "暴恐内容").decision()).isEqualTo(ModerationDecision.BLOCK);

        FakeProvider reviewer = new FakeProvider("fake", raw(ModerationDecision.REVIEW, "广告"));
        resolveTo("fake", "block");
        assertThat(moderate(service(reviewer), "广告内容").decision()).isEqualTo(ModerationDecision.REVIEW);
    }

    @Test
    void nullActionDefaultsToBlockSemantics() {
        when(policyService.resolve(any(), any())).thenReturn(Mono.just(
                new ResolvedModeration("fake", null, Map.of())));
        FakeProvider provider = new FakeProvider("fake", raw(ModerationDecision.BLOCK, "广告"));

        assertThat(moderate(service(provider), "赌博").decision()).isEqualTo(ModerationDecision.BLOCK);
    }

    @Test
    void providerFailureFailsOpen() {
        resolveTo("fake", "BLOCK");
        FakeProvider provider = new FakeProvider("fake", null, new IllegalStateException("upstream 503"));

        ModerationResult result = moderate(service(provider), "任意内容");

        assertThat(result.decision()).isEqualTo(ModerationDecision.PASS);
        assertThat(result.provider()).isEqualTo("fake");
        assertThat(result.detail()).contains("moderation-error").contains("IllegalStateException");
    }

    @Test
    void emptyProviderResultFailsOpen() {
        resolveTo("quiet", "BLOCK");
        // 提供方返回空信号（违反 SPI 约定）时不得让主链静默中断
        ModerationProvider quiet = new ModerationProvider() {
            @Override
            public String name() {
                return "quiet";
            }

            @Override
            public Mono<ModerationResult> moderate(String text, ModerationContext context) {
                return Mono.empty();
            }
        };

        ModerationResult result = moderate(service(quiet), "任意内容");

        assertThat(result.decision()).isEqualTo(ModerationDecision.PASS);
        assertThat(result.provider()).isEqualTo("quiet");
    }

    @Test
    void providerThrowingOnAssemblyFailsOpen() {
        resolveTo("fake", "BLOCK");
        // Mono.just(null) 在装配期抛 NPE，模拟提供方实现缺陷
        FakeProvider provider = new FakeProvider("fake", null);

        ModerationResult result = moderate(service(provider), "任意内容");

        assertThat(result.decision()).isEqualTo(ModerationDecision.PASS);
        assertThat(result.provider()).isEqualTo("fake");
        assertThat(result.detail()).contains("moderation-error");
    }

    @Test
    void propagatesContextToProvider() {
        resolveTo("fake", "BLOCK");
        FakeProvider provider = new FakeProvider("fake", raw(ModerationDecision.PASS));

        moderate(service(provider), "待审文本");

        ModerationContext ctx = provider.lastContext;
        assertThat(ctx).isNotNull();
        assertThat(ctx.text()).isEqualTo("待审文本");
        assertThat(ctx.direction()).isEqualTo("INPUT");
        assertThat(ctx.capability()).isEqualTo(Capability.CHAT);
        assertThat(ctx.logicalModel()).isEqualTo("deepseek-chat");
        assertThat(ctx.tenant()).isEqualTo("tenant-a");
        assertThat(ctx.string("k", "fallback")).isEqualTo("v");
    }
}
