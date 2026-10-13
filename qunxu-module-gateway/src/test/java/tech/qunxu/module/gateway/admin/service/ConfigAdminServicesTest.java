package tech.qunxu.module.gateway.admin.service;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import tech.qunxu.module.gateway.admin.AdminPage;
import tech.qunxu.module.gateway.admin.BatchChannelCreateRequest;
import tech.qunxu.module.gateway.admin.BatchChannelCreateResult;
import tech.qunxu.module.gateway.common.crypto.CredentialCipher;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.config.ConfigVersionPublisher;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.health.HealthStore;
import tech.qunxu.module.gateway.persistence.entity.LogicalModelEntity;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;
import tech.qunxu.module.gateway.persistence.entity.ProviderEntity;
import tech.qunxu.module.gateway.persistence.entity.SensitiveWordEntity;
import tech.qunxu.module.gateway.persistence.mapper.LogicalModelMapper;
import tech.qunxu.module.gateway.persistence.mapper.ModelChannelMapper;
import tech.qunxu.module.gateway.persistence.mapper.ProviderMapper;
import tech.qunxu.module.gateway.persistence.mapper.SensitiveWordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 配置类 Admin 服务单元测试（M8e）：以 mock Mapper 驱动 {@link AbstractConfigAdminService} 骨架与三个
 * 代表性子类的差异化钩子。
 *
 * <p>重点覆盖三条不可回退的安全/一致性约束：</p>
 * <ul>
 *   <li><b>凭证</b>：明文加密落库、密文幂等不二次加密、读取一律脱敏为 {@code ***}（即使开启
 *       {@code expose-credentials} 也只暴露密文）、更新回传掩码时该列不进入 SET；</li>
 *   <li><b>引用完整性</b>：供应商被通道引用时删除返回 409，通道指向不存在/已禁用的模型或供应商时返回 400，
 *       通道删除后清理 Redis 健康键且清理失败不影响删除结果；</li>
 *   <li><b>缓存一致性</b>：任何写入都广播对应类型的配置失效消息，批量导入零新增时不广播。</li>
 * </ul>
 */
class ConfigAdminServicesTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private ProviderMapper providerMapper;
    private ModelChannelMapper channelMapper;
    private LogicalModelMapper modelMapper;
    private SensitiveWordMapper wordMapper;
    private HealthStore healthStore;
    private ConfigVersionPublisher publisher;
    private GatewayProperties properties;
    private CredentialCipher cipher;

    private ProviderAdminService providerService;
    private ModelChannelAdminService channelService;
    private SensitiveWordAdminService wordService;

    @BeforeEach
    void setUp() {
        providerMapper = mock(ProviderMapper.class);
        channelMapper = mock(ModelChannelMapper.class);
        modelMapper = mock(LogicalModelMapper.class);
        wordMapper = mock(SensitiveWordMapper.class);
        healthStore = mock(HealthStore.class);
        publisher = mock(ConfigVersionPublisher.class);
        properties = new GatewayProperties();
        cipher = new CredentialCipher(properties);
        ObjectMapper objectMapper = new ObjectMapper();

        // 变更后广播必须返回非 null 的 Mono，否则 flatMap 阶段 NPE
        when(publisher.publish(anyString())).thenReturn(Mono.empty());

        providerService = new ProviderAdminService(providerMapper, channelMapper, cipher, publisher,
                properties, objectMapper);
        channelService = new ModelChannelAdminService(channelMapper, modelMapper, providerMapper, healthStore,
                publisher, properties, objectMapper);
        wordService = new SensitiveWordAdminService(wordMapper, publisher, properties, objectMapper);
    }

    /** 让 mock 的分页查询回填记录与总数，返回调用方传入的同一个 Page 实例。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void stubPage(BaseMapper mapper, List<?> records, long total) {
        when(mapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page page = inv.getArgument(0);
            page.setRecords(records);
            page.setTotal(total);
            return page;
        });
    }

    private static ProviderEntity provider(Long id, String code, String name, String credentials) {
        ProviderEntity entity = new ProviderEntity();
        entity.setId(id);
        entity.setCode(code);
        entity.setName(name);
        entity.setBaseUrl("https://api.example.com/v1");
        entity.setAuthType("BEARER");
        entity.setCredentials(credentials);
        entity.setStatus(1);
        entity.setPriority(100);
        entity.setVersion(0);
        return entity;
    }

    private static ModelChannelEntity channel(Long id, Long modelId, Long providerId) {
        ModelChannelEntity entity = new ModelChannelEntity();
        entity.setId(id);
        entity.setLogicalModelId(modelId);
        entity.setProviderId(providerId);
        entity.setUpstreamModel("deepseek-chat");
        entity.setProtocol("OPENAI_COMPAT");
        entity.setWeight(100);
        entity.setPriority(100);
        entity.setEnabled(1);
        entity.setVersion(0);
        return entity;
    }

    private static SensitiveWordEntity word(String text) {
        SensitiveWordEntity entity = new SensitiveWordEntity();
        entity.setWord(text);
        return entity;
    }

    /** 断言流以携带指定错误码的 {@link GatewayException} 终止。 */
    private static void assertGatewayError(StepVerifier.FirstStep<?> step, ErrorCode expected) {
        step.expectErrorSatisfies(error -> {
            assertThat(error).isInstanceOf(GatewayException.class);
            assertThat(((GatewayException) error).getErrorCode()).isEqualTo(expected);
        }).verify(TIMEOUT);
    }

    // ================================================================== 供应商

    @Nested
    class ProviderCrud {

        @Test
        void createEncryptsCredentialsAndFillsDefaults() {
            when(providerMapper.selectCount(any())).thenReturn(0L);
            AtomicReference<String> stored = new AtomicReference<>();
            when(providerMapper.insert(any(ProviderEntity.class))).thenAnswer(inv -> {
                ProviderEntity e = inv.getArgument(0);
                // sanitize 会在返回前把入参对象的凭证改写成掩码，故必须在 insert 时刻取快照
                stored.set(e.getCredentials());
                e.setId(7L);
                return 1;
            });

            ProviderEntity input = provider(null, "deepseek", "  DeepSeek  ", "sk-plaintext");
            input.setAuthType(null);
            input.setStatus(null);
            input.setPriority(null);

            ProviderEntity saved = providerService.create(input).block(TIMEOUT);

            assertThat(saved).isNotNull();
            assertThat(saved.getCredentials()).isEqualTo("***");
            assertThat(stored.get()).isNotEqualTo("sk-plaintext");
            assertThat(cipher.isEncrypted(stored.get())).isTrue();
            assertThat(cipher.decrypt(stored.get())).isEqualTo("sk-plaintext");
            // 默认值与归一化
            assertThat(input.getName()).isEqualTo("DeepSeek");
            assertThat(input.getCode()).isEqualTo("DEEPSEEK");
            assertThat(input.getAuthType()).isEqualTo("BEARER");
            assertThat(input.getStatus()).isEqualTo(1);
            assertThat(input.getPriority()).isEqualTo(100);
            assertThat(input.getVersion()).isEqualTo(0);
            verify(publisher).publish(ConfigVersionPublisher.TYPE_PROVIDER);
        }

        @Test
        void createDoesNotReEncryptAlreadyEncryptedCredentials() {
            when(providerMapper.selectCount(any())).thenReturn(0L);
            String alreadyEncrypted = cipher.encrypt("sk-plaintext");
            AtomicReference<String> stored = new AtomicReference<>();
            when(providerMapper.insert(any(ProviderEntity.class))).thenAnswer(inv -> {
                stored.set(((ProviderEntity) inv.getArgument(0)).getCredentials());
                return 1;
            });

            // 幂等重放：管理端把密文原样回传时不得二次加密，否则解密会得到乱码
            providerService.create(provider(null, "DEEPSEEK", "DeepSeek", alreadyEncrypted)).block(TIMEOUT);

            assertThat(stored.get()).isEqualTo(alreadyEncrypted);
            assertThat(cipher.decrypt(stored.get())).isEqualTo("sk-plaintext");
        }

        @Test
        void createRejectsUnknownProviderCode() {
            assertGatewayError(StepVerifier.create(providerService.create(provider(null, "NOT_A_PROVIDER", "x", null))), ErrorCode.BAD_REQUEST);
            verify(providerMapper, never()).insert(any(ProviderEntity.class));
        }

        @Test
        void createRejectsUrlWithoutSupportedScheme() {
            ProviderEntity input = provider(null, "DEEPSEEK", "DeepSeek", null);
            input.setBaseUrl("ftp://api.example.com");

            StepVerifier.create(providerService.create(input))
                    .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(GatewayException.class)
                            .hasMessageContaining("http(s)://")).verify();
        }

        @Test
        void createRejectsInvalidFlag() {
            ProviderEntity input = provider(null, "DEEPSEEK", "DeepSeek", null);
            input.setStatus(2);

            StepVerifier.create(providerService.create(input))
                    .expectErrorSatisfies(error -> assertThat(error).hasMessageContaining("只能为 0 或 1")).verify();
        }

        @Test
        void createRejectsDuplicateCodeAndName() {
            when(providerMapper.selectCount(any())).thenReturn(1L);

            StepVerifier.create(providerService.create(provider(null, "DEEPSEEK", "DeepSeek", null)))
                    .expectErrorSatisfies(error -> {
                        assertThat(((GatewayException) error).getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                        assertThat(error).hasMessageContaining("编码 + 名称唯一");
                    }).verify();
            verify(providerMapper, never()).insert(any(ProviderEntity.class));
        }

        @Test
        void getByIdMasksCredentialsByDefault() {
            when(providerMapper.selectById(1L))
                    .thenReturn(provider(1L, "DEEPSEEK", "DeepSeek", cipher.encrypt("sk-plaintext")));

            ProviderEntity found = providerService.getById(1L).block(TIMEOUT);

            assertThat(found).isNotNull();
            assertThat(found.getCredentials()).isEqualTo("***");
        }

        @Test
        void getByIdExposesOnlyCiphertextWhenConfigured() {
            properties.getAdmin().setExposeCredentials(true);
            String encrypted = cipher.encrypt("sk-plaintext");
            when(providerMapper.selectById(1L)).thenReturn(provider(1L, "DEEPSEEK", "DeepSeek", encrypted));

            ProviderEntity found = providerService.getById(1L).block(TIMEOUT);

            // 即便显式开启，也只回传密文，明文永不出网关
            assertThat(found.getCredentials()).isEqualTo(encrypted);
            assertThat(found.getCredentials()).doesNotContain("sk-plaintext");
        }

        @Test
        void getByIdLeavesBlankCredentialsUntouched() {
            when(providerMapper.selectById(1L)).thenReturn(provider(1L, "DEEPSEEK", "DeepSeek", null));

            assertThat(providerService.getById(1L).block(TIMEOUT).getCredentials()).isNull();
        }

        @Test
        void getByIdMissingRowYieldsNotFound() {
            when(providerMapper.selectById(99L)).thenReturn(null);

            assertGatewayError(StepVerifier.create(providerService.getById(99L)), ErrorCode.NOT_FOUND);
        }

        @Test
        void updateKeepsOriginalCredentialsWhenMaskResubmitted() {
            ProviderEntity existing = provider(1L, "DEEPSEEK", "DeepSeek", cipher.encrypt("sk-plaintext"));
            when(providerMapper.selectById(1L)).thenReturn(existing);
            AtomicReference<String> updated = new AtomicReference<>("unset");
            when(providerMapper.updateById(any(ProviderEntity.class))).thenAnswer(inv -> {
                updated.set(((ProviderEntity) inv.getArgument(0)).getCredentials());
                return 1;
            });

            ProviderEntity patch = new ProviderEntity();
            patch.setName("DeepSeek 生产");
            patch.setCredentials("***");

            ProviderEntity result = providerService.update(1L, patch).block(TIMEOUT);

            // 掩码回传 → 该列不进入 SET 子句，真实凭证不会被前端回显覆盖
            assertThat(updated.get()).isNull();
            assertThat(result.getCredentials()).isEqualTo("***");
            assertThat(patch.getId()).isEqualTo(1L);
            verify(publisher).publish(ConfigVersionPublisher.TYPE_PROVIDER);
        }

        @Test
        void updateEncryptsNewlySubmittedCredentials() {
            when(providerMapper.selectById(1L)).thenReturn(provider(1L, "DEEPSEEK", "DeepSeek", null));
            AtomicReference<String> updated = new AtomicReference<>();
            when(providerMapper.updateById(any(ProviderEntity.class))).thenAnswer(inv -> {
                updated.set(((ProviderEntity) inv.getArgument(0)).getCredentials());
                return 1;
            });

            ProviderEntity patch = new ProviderEntity();
            patch.setCredentials("sk-rotated");
            providerService.update(1L, patch).block(TIMEOUT);

            assertThat(cipher.isEncrypted(updated.get())).isTrue();
            assertThat(cipher.decrypt(updated.get())).isEqualTo("sk-rotated");
        }

        @Test
        void updateYieldsConflictWhenNoRowAffected() {
            when(providerMapper.selectById(1L)).thenReturn(provider(1L, "DEEPSEEK", "DeepSeek", null));
            when(providerMapper.updateById(any(ProviderEntity.class))).thenReturn(0);

            ProviderEntity patch = new ProviderEntity();
            patch.setVersion(0);
            // 乐观锁版本已变更或行已被删除 → 409，而非静默成功
            assertGatewayError(StepVerifier.create(providerService.update(1L, patch)),
                    ErrorCode.CONFLICT);
        }

        @Test
        void updateRejectsIllegalAuthType() {
            when(providerMapper.selectById(1L)).thenReturn(provider(1L, "DEEPSEEK", "DeepSeek", null));

            ProviderEntity patch = new ProviderEntity();
            patch.setAuthType("BASIC");

            StepVerifier.create(providerService.update(1L, patch))
                    .expectErrorSatisfies(error -> assertThat(error).hasMessageContaining("取值非法")).verify();
        }

        @Test
        void deleteRejectedWhileReferencedByChannels() {
            when(providerMapper.selectById(1L)).thenReturn(provider(1L, "DEEPSEEK", "DeepSeek", null));
            when(channelMapper.selectCount(any())).thenReturn(3L);

            StepVerifier.create(providerService.delete(1L))
                    .expectErrorSatisfies(error -> {
                        assertThat(((GatewayException) error).getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                        assertThat(error).hasMessageContaining("3 个模型通道引用");
                    }).verify();
            verify(providerMapper, never()).deleteById(1L);
            verify(publisher, never()).publish(anyString());
        }

        @Test
        void deleteSucceedsWhenUnreferenced() {
            when(providerMapper.selectById(1L)).thenReturn(provider(1L, "DEEPSEEK", "DeepSeek", null));
            when(channelMapper.selectCount(any())).thenReturn(0L);

            StepVerifier.create(providerService.delete(1L)).verifyComplete();

            verify(providerMapper).deleteById(1L);
            verify(publisher).publish(ConfigVersionPublisher.TYPE_PROVIDER);
        }

        @Test
        void queryCapsPageSizeAndNormalizesPageNumber() {
            properties.getAdmin().setMaxPageSize(50);
            stubPage(providerMapper, List.of(provider(1L, "DEEPSEEK", "DeepSeek", "enc::v1::abc")), 120L);

            AdminPage<ProviderEntity> result = providerService.query("deep", 1, 0, 5000).block(TIMEOUT);

            assertThat(result).isNotNull();
            assertThat(result.page()).isEqualTo(1);
            assertThat(result.size()).isEqualTo(50);
            assertThat(result.total()).isEqualTo(120L);
            assertThat(result.totalPages()).isEqualTo(3);
            assertThat(result.items()).hasSize(1);
            assertThat(result.items().get(0).getCredentials()).isEqualTo("***");
        }

        @Test
        void queryAllUsesDefaultPageSizeWhenUnset() {
            stubPage(providerMapper, List.of(), 0L);

            AdminPage<ProviderEntity> result = providerService.query(null, null, null, null).block(TIMEOUT);

            assertThat(result.page()).isEqualTo(1);
            assertThat(result.size()).isEqualTo(20);
        }

        @Test
        void listSanitizesEveryRow() {
            when(providerMapper.selectList(any())).thenReturn(List.of(
                    provider(1L, "DEEPSEEK", "DeepSeek", cipher.encrypt("a")),
                    provider(2L, "ZHIPU", "智谱", cipher.encrypt("b"))));

            List<ProviderEntity> rows = providerService.queryAll(null, null).block(TIMEOUT);

            assertThat(rows).hasSize(2).allSatisfy(row -> assertThat(row.getCredentials()).isEqualTo("***"));
        }
    }

    // ================================================================== 模型通道

    @Nested
    class ChannelCrud {

        private void stubValidReferences() {
            LogicalModelEntity model = new LogicalModelEntity();
            model.setId(10L);
            model.setName("deepseek-chat");
            when(modelMapper.selectById(10L)).thenReturn(model);
            when(providerMapper.selectById(20L)).thenReturn(provider(20L, "DEEPSEEK", "DeepSeek", null));
        }

        @Test
        void createFillsDefaultsAndNormalizesProtocol() {
            stubValidReferences();
            AtomicReference<ModelChannelEntity> stored = new AtomicReference<>();
            when(channelMapper.insert(any(ModelChannelEntity.class))).thenAnswer(inv -> {
                stored.set(inv.getArgument(0));
                return 1;
            });

            ModelChannelEntity input = channel(null, 10L, 20L);
            input.setProtocol("openai_compat");
            input.setWeight(null);
            input.setPriority(null);
            input.setEnabled(null);

            channelService.create(input).block(TIMEOUT);

            assertThat(stored.get().getProtocol()).isEqualTo("OPENAI_COMPAT");
            assertThat(stored.get().getWeight()).isEqualTo(100);
            assertThat(stored.get().getPriority()).isEqualTo(100);
            assertThat(stored.get().getEnabled()).isEqualTo(1);
            assertThat(stored.get().getVersion()).isEqualTo(0);
            verify(publisher).publish(ConfigVersionPublisher.TYPE_CHANNEL);
        }

        @Test
        void createRejectsMissingLogicalModelReference() {
            when(modelMapper.selectById(10L)).thenReturn(null);

            StepVerifier.create(channelService.create(channel(null, 10L, 20L)))
                    .expectErrorSatisfies(error -> {
                        assertThat(((GatewayException) error).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                        assertThat(error).hasMessageContaining("引用的逻辑模型不存在");
                    }).verify();
            verify(channelMapper, never()).insert(any(ModelChannelEntity.class));
        }

        @Test
        void createRejectsDisabledProvider() {
            LogicalModelEntity model = new LogicalModelEntity();
            model.setId(10L);
            when(modelMapper.selectById(10L)).thenReturn(model);
            ProviderEntity disabled = provider(20L, "DEEPSEEK", "DeepSeek", null);
            disabled.setStatus(0);
            when(providerMapper.selectById(20L)).thenReturn(disabled);

            StepVerifier.create(channelService.create(channel(null, 10L, 20L)))
                    .expectErrorSatisfies(error -> assertThat(error).hasMessageContaining("已禁用")).verify();
        }

        @Test
        void createRejectsMissingUpstreamModel() {
            ModelChannelEntity input = channel(null, 10L, 20L);
            input.setUpstreamModel("   ");

            StepVerifier.create(channelService.create(input))
                    .expectErrorSatisfies(error -> assertThat(error).hasMessageContaining("不能为空")).verify();
        }

        @Test
        void createRejectsMalformedConfigJson() {
            stubValidReferences();
            ModelChannelEntity input = channel(null, 10L, 20L);
            input.setConfigJson("{不是 JSON");

            StepVerifier.create(channelService.create(input))
                    .expectErrorSatisfies(error -> {
                        assertThat(((GatewayException) error).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                        assertThat(error).hasMessageContaining("不是合法 JSON");
                    }).verify();
        }

        @Test
        void createRejectsNegativeWeightAndUnknownProtocol() {
            stubValidReferences();
            ModelChannelEntity negativeWeight = channel(null, 10L, 20L);
            negativeWeight.setWeight(-1);
            StepVerifier.create(channelService.create(negativeWeight))
                    .expectErrorSatisfies(error -> assertThat(error).hasMessageContaining("不能为负数")).verify();

            ModelChannelEntity badProtocol = channel(null, 10L, 20L);
            badProtocol.setProtocol("GRPC");
            StepVerifier.create(channelService.create(badProtocol))
                    .expectErrorSatisfies(error -> assertThat(error).hasMessageContaining("协议 protocol 取值非法")).verify();
        }

        @Test
        void updateRevalidatesReferencesOnlyWhenChanged() {
            when(channelMapper.selectById(5L)).thenReturn(channel(5L, 10L, 20L));
            when(channelMapper.updateById(any(ModelChannelEntity.class))).thenReturn(1);

            // 仅改上游模型名，不应触发对逻辑模型/供应商的存在性查询
            ModelChannelEntity patch = new ModelChannelEntity();
            patch.setUpstreamModel(" deepseek-reasoner ");
            channelService.update(5L, patch).block(TIMEOUT);

            verify(modelMapper, never()).selectById(any());
            verify(providerMapper, never()).selectById(any());
            verify(publisher).publish(ConfigVersionPublisher.TYPE_CHANNEL);
        }

        @Test
        void deleteCleansUpRedisHealthKey() {
            when(channelMapper.selectById(5L)).thenReturn(channel(5L, 10L, 20L));
            when(healthStore.delete(5L)).thenReturn(Mono.empty());

            StepVerifier.create(channelService.delete(5L)).verifyComplete();

            // 通道 ID 可能被复用，遗留的剔除/时延状态必须一并清掉
            verify(healthStore).delete(5L);
            verify(publisher).publish(ConfigVersionPublisher.TYPE_CHANNEL);
        }

        @Test
        void deleteStillSucceedsWhenHealthCleanupFails() {
            when(channelMapper.selectById(5L)).thenReturn(channel(5L, 10L, 20L));
            when(healthStore.delete(5L)).thenReturn(Mono.error(new IllegalStateException("redis down")));

            StepVerifier.create(channelService.delete(5L)).verifyComplete();

            verify(channelMapper).deleteById(5L);
        }

        @Test
        void querySortsByPriorityThenWeightThenId() {
            AtomicReference<com.baomidou.mybatisplus.core.conditions.Wrapper<ModelChannelEntity>> captured =
                    new AtomicReference<>();
            when(channelMapper.selectPage(any(), any())).thenAnswer(inv -> {
                captured.set(inv.getArgument(1));
                Page<ModelChannelEntity> page = inv.getArgument(0);
                page.setRecords(List.of(channel(5L, 10L, 20L)));
                page.setTotal(1L);
                return page;
            });

            AdminPage<ModelChannelEntity> result =
                    channelService.query(10L, 20L, "openai_compat", 1, 1, 20).block(TIMEOUT);

            assertThat(result.items()).hasSize(1);
            assertThat(result.total()).isEqualTo(1L);
            // 优先级升序 → 权重降序 → ID 升序，与负载均衡的选择次序一致
            assertThat(captured.get().getSqlSegment())
                    .contains("ORDER BY priority ASC", "weight DESC", "id ASC")
                    .contains("logical_model_id", "provider_id", "protocol", "enabled");
        }
    }

    @Nested
    class ChannelBatchCreate {

        @Test
        void autoCreatesLogicalModelsAndSkipsExistingChannels() {
            when(providerMapper.selectById(20L)).thenReturn(provider(20L, "DEEPSEEK", "DeepSeek", null));

            // 已存在一条 upstream_model=deepseek-chat 的通道
            ModelChannelEntity existing = channel(99L, 10L, 20L);
            existing.setUpstreamModel("deepseek-chat");
            when(channelMapper.selectList(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(List.of(existing));

            // 逻辑模型：deepseek-chat 已存在（复用），其余两个自动创建
            LogicalModelEntity reused = new LogicalModelEntity();
            reused.setId(10L);
            reused.setName("deepseek-chat");
            reused.setCapability("CHAT");
            reused.setStatus(1);
            when(modelMapper.selectOne(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(reused, null, null);

            List<LogicalModelEntity> createdModels = new ArrayList<>();
            when(modelMapper.insert(any(LogicalModelEntity.class))).thenAnswer(inv -> {
                LogicalModelEntity entity = inv.getArgument(0);
                entity.setId(100L + createdModels.size());
                createdModels.add(entity);
                return 1;
            });
            List<ModelChannelEntity> inserted = new ArrayList<>();
            when(channelMapper.insert(any(ModelChannelEntity.class))).thenAnswer(inv -> {
                inserted.add(inv.getArgument(0));
                return 1;
            });

            BatchChannelCreateResult result = channelService.batchCreate(new BatchChannelCreateRequest(
                    20L, null, null, null, null, null, null,
                    Arrays.asList("deepseek-chat", "deepseek-reasoner", " deepseek-coder "))).block(TIMEOUT);

            assertThat(result.total()).isEqualTo(3);
            assertThat(result.created()).hasSize(2);
            assertThat(result.skipped()).containsExactly("deepseek-chat");
            assertThat(result.createdModels()).hasSize(2);
            // 自动创建的逻辑模型：名字 = 上游模型名，能力默认 CHAT、默认启用
            assertThat(createdModels).extracting(LogicalModelEntity::getName)
                    .containsExactly("deepseek-reasoner", "deepseek-coder");
            assertThat(createdModels).allSatisfy(m -> {
                assertThat(m.getCapability()).isEqualTo("CHAT");
                assertThat(m.getStatus()).isEqualTo(1);
            });
            // 通道公共字段默认值统一补齐，且分别广播一次模型/通道配置失效
            assertThat(inserted).extracting(ModelChannelEntity::getUpstreamModel)
                    .containsExactly("deepseek-reasoner", "deepseek-coder");
            assertThat(inserted).allSatisfy(ch -> {
                assertThat(ch.getProviderId()).isEqualTo(20L);
                assertThat(ch.getProtocol()).isEqualTo("OPENAI_COMPAT");
                assertThat(ch.getWeight()).isEqualTo(100);
                assertThat(ch.getPriority()).isEqualTo(100);
                assertThat(ch.getEnabled()).isEqualTo(1);
            });
            verify(publisher).publish(ConfigVersionPublisher.TYPE_LOGICAL_MODEL);
            verify(publisher).publish(ConfigVersionPublisher.TYPE_CHANNEL);
        }

        @Test
        void reusesExistingLogicalModelWhenNameCollides() {
            when(providerMapper.selectById(20L)).thenReturn(provider(20L, "DEEPSEEK", "DeepSeek", null));
            when(channelMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
            LogicalModelEntity existing = new LogicalModelEntity();
            existing.setId(10L);
            existing.setName("deepseek-reasoner");
            existing.setCapability("CHAT");
            when(modelMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(existing);

            BatchChannelCreateResult result = channelService.batchCreate(new BatchChannelCreateRequest(
                    20L, "CHAT", null, null, null, null, null, List.of("deepseek-reasoner"))).block(TIMEOUT);

            assertThat(result.createdModels()).isEmpty();
            assertThat(result.created()).hasSize(1);
            assertThat(result.created().get(0).getLogicalModelId()).isEqualTo(10L);
            verify(modelMapper, never()).insert(any(LogicalModelEntity.class));
        }

        @Test
        void autoDetectsCapabilityPerModelIgnoringExplicitOverride() {
            when(providerMapper.selectById(20L)).thenReturn(provider(20L, "DEEPSEEK", "DeepSeek", null));
            when(channelMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
            when(modelMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(null);

            List<LogicalModelEntity> createdModels = new ArrayList<>();
            when(modelMapper.insert(any(LogicalModelEntity.class))).thenAnswer(inv -> {
                LogicalModelEntity entity = inv.getArgument(0);
                entity.setId(100L + createdModels.size());
                createdModels.add(entity);
                return 1;
            });
            when(channelMapper.insert(any(ModelChannelEntity.class))).thenAnswer(inv -> {
                ((ModelChannelEntity) inv.getArgument(0)).setId(200L);
                return 1;
            });

            // 显式指定 CHAT 也不会覆盖自动识别出的 EMBEDDING / RERANK
            BatchChannelCreateResult result = channelService.batchCreate(new BatchChannelCreateRequest(
                    20L, "CHAT", null, null, null, null, null,
                    Arrays.asList("gpt-4o", "text-embedding-3-small", "bge-reranker-v2-m3"))).block(TIMEOUT);

            assertThat(result.createdModels())
                    .extracting(m -> m.getName() + "=" + m.getCapability())
                    .containsExactly("gpt-4o=CHAT", "text-embedding-3-small=EMBEDDING", "bge-reranker-v2-m3=RERANK");
        }

        @Test
        void rejectsEmptyModelList() {
            StepVerifier.create(channelService.batchCreate(new BatchChannelCreateRequest(
                            20L, null, null, null, null, null, null, List.of())))
                    .expectErrorSatisfies(error -> {
                        assertThat(((GatewayException) error).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                        assertThat(error).hasMessageContaining("upstream_models");
                    }).verify();
            verify(channelMapper, never()).insert(any(ModelChannelEntity.class));
        }

        @Test
        void rejectsMissingProviderBeforeAnyInsert() {
            when(providerMapper.selectById(20L)).thenReturn(null);

            StepVerifier.create(channelService.batchCreate(new BatchChannelCreateRequest(
                            20L, null, null, null, null, null, null, List.of("deepseek-chat"))))
                    .expectErrorSatisfies(error -> {
                        assertThat(((GatewayException) error).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                        assertThat(error).hasMessageContaining("供应商不存在");
                    }).verify();
            verify(channelMapper, never()).insert(any(ModelChannelEntity.class));
            verify(modelMapper, never()).insert(any(LogicalModelEntity.class));
            verify(publisher, never()).publish(anyString());
        }

        @Test
        void rejectsInvalidCapability() {
            when(providerMapper.selectById(20L)).thenReturn(provider(20L, "DEEPSEEK", "DeepSeek", null));

            StepVerifier.create(channelService.batchCreate(new BatchChannelCreateRequest(
                            20L, "SING", null, null, null, null, null, List.of("deepseek-chat"))))
                    .expectErrorSatisfies(error -> {
                        assertThat(((GatewayException) error).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                        assertThat(error).hasMessageContaining("capability");
                    }).verify();
            verify(channelMapper, never()).insert(any(ModelChannelEntity.class));
        }
    }

    // ================================================================== 敏感词

    @Nested
    class SensitiveWordCrud {

        @Test
        void createAppliesDefaultsAndTrims() {
            AtomicReference<SensitiveWordEntity> stored = new AtomicReference<>();
            when(wordMapper.insert(any(SensitiveWordEntity.class))).thenAnswer(inv -> {
                stored.set(inv.getArgument(0));
                return 1;
            });

            SensitiveWordEntity input = word("  赌博  ");
            wordService.create(input).block(TIMEOUT);

            assertThat(stored.get().getWord()).isEqualTo("赌博");
            assertThat(stored.get().getCategory()).isEqualTo("其他");
            assertThat(stored.get().getLevel()).isEqualTo("MEDIUM");
            assertThat(stored.get().getAction()).isEqualTo("BLOCK");
            assertThat(stored.get().getEnabled()).isEqualTo(1);
            verify(publisher).publish(ConfigVersionPublisher.TYPE_SENSITIVE_WORD);
        }

        @Test
        void createRejectsUnknownLevelAndAction() {
            SensitiveWordEntity input = word("赌博");
            input.setLevel("CRITICAL");
            StepVerifier.create(wordService.create(input))
                    .expectErrorSatisfies(error -> assertThat(error).hasMessageContaining("级别 level 取值非法")).verify();

            SensitiveWordEntity other = word("赌博");
            other.setAction("DELETE");
            StepVerifier.create(wordService.create(other))
                    .expectErrorSatisfies(error -> assertThat(error).hasMessageContaining("命中动作 action 取值非法")).verify();
        }

        @Test
        void importBatchDeduplicatesWithinSubmissionAndAgainstStore() {
            when(wordMapper.selectList(any())).thenReturn(List.of(word("赌博")));
            List<SensitiveWordEntity> submitted =
                    List.of(word("赌博"), word("赌博"), word(" 赌博 "), word("新词"));

            SensitiveWordAdminService.BatchResult result = wordService.importBatch(submitted).block(TIMEOUT);

            assertThat(result.submitted()).isEqualTo(4);
            assertThat(result.inserted()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(3);
            assertThat(result.warnings()).isEmpty();
            verify(wordMapper, times(1)).insert(any(SensitiveWordEntity.class));
            verify(publisher).publish(ConfigVersionPublisher.TYPE_SENSITIVE_WORD);
        }

        @Test
        void importBatchCountsIllegalWordsAsSkipped() {
            when(wordMapper.selectList(any())).thenReturn(List.of());
            String tooLong = "违".repeat(256);
            List<SensitiveWordEntity> submitted = List.of(word(tooLong), word("正常词"));

            SensitiveWordAdminService.BatchResult result = wordService.importBatch(submitted).block(TIMEOUT);

            assertThat(result.submitted()).isEqualTo(2);
            assertThat(result.inserted()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(1);
            assertThat(result.warnings()).hasSize(1);
            assertThat(result.warnings().get(0)).contains("长度超出上限 255");
        }

        @Test
        void importBatchReportsBlankWordsWithoutTouchingStore() {
            SensitiveWordAdminService.BatchResult result =
                    wordService.importBatch(List.of(word(null), word("   "))).block(TIMEOUT);

            assertThat(result.submitted()).isEqualTo(2);
            assertThat(result.inserted()).isZero();
            assertThat(result.warnings()).containsExactly("存在 word 为空的词条，已跳过");
            // 候选词为空时提前返回，不查库也不广播
            verifyNoInteractions(wordMapper);
            verify(publisher, never()).publish(anyString());
        }

        @Test
        void importBatchCapsWarnings() {
            List<SensitiveWordEntity> submitted = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                submitted.add(word(("词" + i).repeat(200)));
            }

            SensitiveWordAdminService.BatchResult result = wordService.importBatch(submitted).block(TIMEOUT);

            assertThat(result.warnings()).hasSize(21);
            assertThat(result.warnings().get(20)).contains("其余 5 条警告已省略");
        }

        @Test
        void importBatchRejectsOversizeSubmission() {
            List<SensitiveWordEntity> submitted = IntStream.rangeClosed(0, SensitiveWordAdminService.MAX_BATCH)
                    .mapToObj(i -> word("词" + i))
                    .toList();

            StepVerifier.create(wordService.importBatch(submitted))
                    .expectErrorSatisfies(error -> {
                        assertThat(((GatewayException) error).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                        assertThat(error).hasMessageContaining("单次批量导入上限");
                    }).verify();
            verifyNoInteractions(wordMapper);
        }

        @Test
        void importBatchHandlesEmptyAndNullInput() {
            for (List<SensitiveWordEntity> input : Arrays.asList(List.<SensitiveWordEntity>of(), null)) {
                SensitiveWordAdminService.BatchResult result = wordService.importBatch(input).block(TIMEOUT);
                assertThat(result.submitted()).isZero();
                assertThat(result.inserted()).isZero();
                assertThat(result.skipped()).isZero();
            }
            verifyNoInteractions(wordMapper);
            verify(publisher, never()).publish(anyString());
        }

        @Test
        void importBatchDoesNotPublishWhenNothingInserted() {
            when(wordMapper.selectList(any())).thenReturn(List.of(word("赌博")));

            SensitiveWordAdminService.BatchResult result = wordService.importBatch(List.of(word("赌博"))).block(TIMEOUT);

            assertThat(result.inserted()).isZero();
            verify(publisher, never()).publish(anyString());
        }

        @Test
        void queryNormalizesLevelFilterAndSanitizesNothing() {
            stubPage(wordMapper, List.of(word("赌博")), 1L);

            AdminPage<SensitiveWordEntity> result =
                    wordService.query("赌", "赌博", "high", 1, 1, 10).block(TIMEOUT);

            assertThat(result.items()).hasSize(1);
            assertThat(result.items().get(0).getWord()).isEqualTo("赌博");
        }

        @Test
        void updateRejectsNullIdAndNullBody() {
            assertGatewayError(StepVerifier.create(wordService.update(null, word("x"))),
                    ErrorCode.BAD_REQUEST);
            assertGatewayError(StepVerifier.create(wordService.update(1L, null)),
                    ErrorCode.BAD_REQUEST);
            assertGatewayError(StepVerifier.create(wordService.delete(null)),
                    ErrorCode.BAD_REQUEST);
        }
    }

    @Test
    void pageToleratesMapperReturningEmptyPage() {
        // selectPage 未回填记录时不得抛 NPE
        when(providerMapper.selectPage(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        AdminPage<ProviderEntity> result = providerService.query(null, null, 1, 10).block(TIMEOUT);

        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isZero();
    }

    @Test
    void pageArgumentCarriesNormalizedPaging() {
        AtomicReference<IPage<ProviderEntity>> captured = new AtomicReference<>();
        when(providerMapper.selectPage(any(), any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return inv.getArgument(0);
        });

        providerService.query(null, null, 3, 10_000).block(TIMEOUT);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getCurrent()).isEqualTo(3L);
        assertThat(captured.get().getSize()).isEqualTo(properties.getAdmin().getMaxPageSize());
    }
}
