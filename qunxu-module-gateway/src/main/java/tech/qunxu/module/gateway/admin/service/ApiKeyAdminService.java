package tech.qunxu.module.gateway.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tech.qunxu.framework.security.core.LoginUser;
import tech.qunxu.framework.security.core.SecurityUtils;
import tech.qunxu.module.gateway.admin.AdminPage;
import tech.qunxu.module.gateway.admin.ApiKeyCreated;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.config.ConfigVersionPublisher;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.persistence.entity.ApiKeyEntity;
import tech.qunxu.module.gateway.persistence.mapper.ApiKeyMapper;
import tech.qunxu.module.gateway.persistence.mapper.SystemUserBriefMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 网关 APIKey 管理服务（{@code /admin-api/gateway/api-keys}）。
 *
 * <p><b>权限模型</b>：管理员（{@code super_admin} 角色）可管理<b>所有用户</b>的 APIKey
 * （可按 {@code userId}/{@code userName} 过滤、可代他人创建）；普通用户<b>只能管理自己</b>的
 * APIKey。判定依据是「方法入口处（Servlet 线程）捕获的当前登录用户」，而非
 * {@code subscribeOn(boundedElastic)} 后的工作线程——后者拿不到 ThreadLocal 安全上下文。</p>
 *
 * <p><b>安全模型</b>：</p>
 * <ul>
 *   <li>密钥由系统生成（{@code sk-qx-} + 32 位十六进制随机数），数据库与内存快照只存
 *       SHA-256 摘要；明文仅在创建/轮换响应中一次性返回；</li>
 *   <li>更新接口仅允许修改名称/备注/启用状态/过期时间，归属（{@code userId}）、摘要、前缀
 *       与租户不可修改（如需新密钥走轮换接口）；</li>
 *   <li>写入后发布 {@code api-key} 类型配置失效，让数据面 {@code ApiKeySnapshotService}
 *       秒级重建快照。</li>
 * </ul>
 */
@Service
public class ApiKeyAdminService extends AbstractConfigAdminService<ApiKeyEntity> {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAdminService.class);

    /** 管理员角色编码（与 system 模块 {@code PermissionService.SUPER_ADMIN_ROLE_CODE} 对齐）。 */
    public static final String SUPER_ADMIN_ROLE = "super_admin";

    /** 密钥前缀（识别 APIKey 来源，也用于展示）。 */
    public static final String KEY_PREFIX = "sk-qx-";
    /** 随机段字节数（32 位十六进制）。 */
    private static final int KEY_RANDOM_BYTES = 16;
    /** 展示前缀额外保留的随机字符数（前缀 = KEY_PREFIX + 前 8 个 hex）。 */
    private static final int KEY_PREFIX_HEX = 8;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String DEFAULT_TENANT = "default";

    private final ApiKeyMapper apiKeyMapper;
    private final SystemUserBriefMapper userMapper;
    private final ConfigVersionPublisher publisher;

    public ApiKeyAdminService(ApiKeyMapper apiKeyMapper,
                              SystemUserBriefMapper userMapper,
                              ConfigVersionPublisher publisher,
                              GatewayProperties properties,
                              ObjectMapper objectMapper) {
        super(properties, objectMapper);
        this.apiKeyMapper = apiKeyMapper;
        this.userMapper = userMapper;
        this.publisher = publisher;
    }

    // ------------------------------------------------------------------ 权限模型

    /** 方法入口捕获的操作者（Servlet 线程），供整个响应式链使用。 */
    private record Operator(Long userId, String username, Long tenantId, boolean admin) {
        boolean canManage(Long ownerId) {
            return admin || (userId != null && userId.equals(ownerId));
        }
    }

    /** 在方法入口（Servlet 线程）捕获当前登录用户，避免响应式线程拿不到安全上下文。 */
    private static Operator captureOperator() {
        LoginUser user = SecurityUtils.getLoginUser().orElse(null);
        if (user == null) {
            return new Operator(null, null, null, false);
        }
        boolean admin = user.getRoleCodes() != null && user.getRoleCodes().contains(SUPER_ADMIN_ROLE);
        return new Operator(user.getId(), user.getUsername(), user.getTenantId(), admin);
    }

    /** 校验操作者有权管理该 APIKey（管理员放行，普通用户必须为所有者）。 */
    private static void requireOwner(Operator op, ApiKeyEntity entity) {
        if (!op.canManage(entity.getUserId())) {
            throw new GatewayException(ErrorCode.FORBIDDEN, "无权操作他人 APIKey: id=" + entity.getId());
        }
    }

    /** 归属解析结果。 */
    private record OwnerResolution(Long userId, String username, String tenant) {
    }

    /**
     * 解析创建请求的归属：管理员可指定 {@code userId}/{@code userName}（缺省为本人），
     * 普通用户一律为本人；解析出的用户名/租户来自 system_users 只读查询或当前登录用户。
     */
    private OwnerResolution resolveOwner(Operator op, Long submittedUserId, String submittedUserName) {
        if (op.admin()) {
            if (submittedUserId != null) {
                SystemUserBriefMapper.UserBrief brief = userMapper.selectById(submittedUserId);
                if (brief == null) {
                    throw badRequest("目标用户不存在: userId=" + submittedUserId);
                }
                return new OwnerResolution(brief.getId(), brief.getUsername(), tenantOf(brief));
            }
            if (hasText(submittedUserName)) {
                SystemUserBriefMapper.UserBrief brief =
                        userMapper.selectByUsername(submittedUserName.trim(), op.tenantId());
                if (brief == null) {
                    throw badRequest("目标用户不存在: " + submittedUserName.trim());
                }
                return new OwnerResolution(brief.getId(), brief.getUsername(), tenantOf(brief));
            }
        }
        if (op.userId() == null) {
            throw new GatewayException(ErrorCode.UNAUTHORIZED, "未登录，无法创建 APIKey");
        }
        return new OwnerResolution(op.userId(), op.username(), tenantOf(op.tenantId()));
    }

    private static String tenantOf(SystemUserBriefMapper.UserBrief brief) {
        return brief.getTenantId() != null ? String.valueOf(brief.getTenantId()) : DEFAULT_TENANT;
    }

    private static String tenantOf(Long tenantId) {
        return tenantId != null ? String.valueOf(tenantId) : DEFAULT_TENANT;
    }

    // ------------------------------------------------------------------ 骨架

    @Override
    protected BaseMapper<ApiKeyEntity> mapper() {
        return apiKeyMapper;
    }

    @Override
    protected String resourceLabel() {
        return "APIKey";
    }

    @Override
    protected Mono<Void> afterChange(String action) {
        return publisher.publish(ConfigVersionPublisher.TYPE_API_KEY);
    }

    /** 禁用抽象骨架的 create：APIKey 必须经 {@link #createKey} 创建以一次性返回明文。 */
    @Override
    public Mono<ApiKeyEntity> create(ApiKeyEntity entity) {
        return Mono.error(badRequest("请使用 POST /admin-api/gateway/api-keys 创建 APIKey（创建响应一次性返回密钥明文）"));
    }

    // ------------------------------------------------------------------ 查询（按归属限定）

    /** 分页查询：管理员可看全部（可按 userId 过滤），普通用户只能看到自己的。 */
    public Mono<AdminPage<ApiKeyEntity>> query(String keyword, Long userId, Integer status,
                                               Integer page, Integer size) {
        Operator op = captureOperator();
        return page(filters(op, keyword, userId, status).orderByDesc("id"), page, size);
    }

    /** 列出全部（不分页），归属规则同 {@link #query}。 */
    public Mono<List<ApiKeyEntity>> queryAll(String keyword, Long userId, Integer status) {
        Operator op = captureOperator();
        return list(filters(op, keyword, userId, status).orderByDesc("id"));
    }

    private QueryWrapper<ApiKeyEntity> filters(Operator op, String keyword, Long userId, Integer status) {
        QueryWrapper<ApiKeyEntity> wrapper = Wrappers.query();
        if (op.admin()) {
            if (userId != null) {
                wrapper.eq("user_id", userId);
            }
        } else {
            if (op.userId() == null) {
                throw new GatewayException(ErrorCode.UNAUTHORIZED, "未登录");
            }
            wrapper.eq("user_id", op.userId());
        }
        if (hasText(keyword)) {
            String like = keyword.trim();
            wrapper.and(w -> w.like("name", like).or().like("user_name", like));
        }
        if (status != null) {
            wrapper.eq("status", status);
        }
        return wrapper;
    }

    // ------------------------------------------------------------------ 单条查询/更新/删除（含归属校验）

    @Override
    public Mono<ApiKeyEntity> getById(Long id) {
        Operator op = captureOperator();
        return super.getById(id).flatMap(entity -> op.canManage(entity.getUserId())
                ? Mono.just(entity)
                : Mono.error(forbidden("无权访问他人 APIKey: id=" + id)));
    }

    @Override
    public Mono<ApiKeyEntity> update(Long id, ApiKeyEntity patch) {
        Operator op = captureOperator();
        if (id == null) {
            return Mono.error(badRequest("缺少资源 ID"));
        }
        if (patch == null) {
            return Mono.error(badRequest("请求体不能为空"));
        }
        return Mono.fromCallable(() -> {
                    ApiKeyEntity existing = requireById(id);
                    requireOwner(op, existing);
                    patch.setId(id);
                    patch.setDeleted(null);
                    patch.setCreatedAt(null);
                    validateUpdate(patch, existing);
                    mergeForUpdate(patch, existing);
                    int rows = apiKeyMapper.updateById(patch);
                    if (rows == 0) {
                        throw new GatewayException(ErrorCode.CONFLICT,
                                "APIKey 更新失败（不存在或版本已变更）: id=" + id);
                    }
                    ApiKeyEntity fresh = apiKeyMapper.selectById(id);
                    log.info("更新APIKey成功: id={}", id);
                    return fresh != null ? fresh : patch;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(saved -> afterChange("update").thenReturn(saved));
    }

    @Override
    public Mono<Void> delete(Long id) {
        Operator op = captureOperator();
        if (id == null) {
            return Mono.error(badRequest("缺少资源 ID"));
        }
        return Mono.fromCallable(() -> {
                    ApiKeyEntity existing = requireById(id);
                    requireOwner(op, existing);
                    beforeDelete(existing);
                    apiKeyMapper.deleteById(id);
                    log.info("删除APIKey成功: id={}", id);
                    return existing;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(existing -> afterChange("delete").then(afterDelete(existing)));
    }

    // ------------------------------------------------------------------ 创建 / 轮换

    /**
     * 创建 APIKey：生成密钥并只存摘要，明文仅在本响应返回一次。
     * 归属规则见 {@link #resolveOwner}。
     */
    public Mono<ApiKeyCreated> createKey(ApiKeyEntity input) {
        Operator op = captureOperator();
        if (input == null) {
            return Mono.error(badRequest("请求体不能为空"));
        }
        return Mono.fromCallable(() -> {
                    input.setId(null);
                    input.setDeleted(null);
                    validateCreate(input);
                    OwnerResolution owner = resolveOwner(op, input.getUserId(), input.getUserName());
                    input.setUserId(owner.userId());
                    input.setUserName(owner.username());
                    input.setTenant(owner.tenant());
                    String plain = generateKey();
                    input.setApiKeyHash(sha256Hex(plain));
                    input.setKeyPrefix(prefixOf(plain));
                    if (input.getStatus() == null) {
                        input.setStatus(1);
                    }
                    apiKeyMapper.insert(input);
                    log.info("新增APIKey成功: id={} owner={}", input.getId(), input.getUserId());
                    return new ApiKeyCreated(input, plain);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(created -> afterChange("create").thenReturn(created));
    }

    /**
     * 轮换 APIKey：为已有密钥生成新明文并替换摘要，旧密钥立即失效。
     * 同样仅本响应返回一次明文。
     */
    public Mono<ApiKeyCreated> rotate(Long id) {
        Operator op = captureOperator();
        if (id == null) {
            return Mono.error(badRequest("缺少资源 ID"));
        }
        return Mono.fromCallable(() -> {
                    ApiKeyEntity existing = requireById(id);
                    requireOwner(op, existing);
                    String plain = generateKey();
                    existing.setApiKeyHash(sha256Hex(plain));
                    existing.setKeyPrefix(prefixOf(plain));
                    existing.setLastUsedAt(null);
                    int rows = apiKeyMapper.updateById(existing);
                    if (rows == 0) {
                        throw new GatewayException(ErrorCode.CONFLICT,
                                "APIKey 轮换失败（不存在或版本已变更）: id=" + id);
                    }
                    log.info("轮换APIKey成功: id={}", id);
                    return new ApiKeyCreated(existing, plain);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(created -> afterChange("rotate").thenReturn(created));
    }

    // ------------------------------------------------------------------ 校验

    @Override
    protected void validateCreate(ApiKeyEntity entity) {
        requireText("APIKey 名称 name", entity.getName(), 128);
        requireRemark(entity.getRemark());
        requireFlag("启用状态 status", entity.getStatus());
        requireExpiresAt(entity.getExpiresAt());
    }

    @Override
    protected void validateUpdate(ApiKeyEntity patch, ApiKeyEntity existing) {
        if (hasText(patch.getName())) {
            requireText("APIKey 名称 name", patch.getName(), 128);
        }
        requireRemark(patch.getRemark());
        requireFlag("启用状态 status", patch.getStatus());
        requireExpiresAt(patch.getExpiresAt());
        // 归属与密钥内容不可通过更新修改
        if (patch.getUserId() != null && !Objects.equals(patch.getUserId(), existing.getUserId())) {
            throw badRequest("APIKey 归属用户不可修改");
        }
        if (patch.getApiKeyHash() != null || patch.getKeyPrefix() != null) {
            throw badRequest("APIKey 密钥内容不可通过更新接口修改（如需新密钥请使用轮换接口）");
        }
        if (hasText(patch.getTenant()) && !patch.getTenant().equals(existing.getTenant())) {
            throw badRequest("APIKey 租户归属不可修改");
        }
    }

    @Override
    protected void mergeForUpdate(ApiKeyEntity patch, ApiKeyEntity existing) {
        if (hasText(patch.getName())) {
            patch.setName(trimmed(patch.getName()));
        }
        // 归属/摘要/前缀/租户为受控字段：置空使其不进入 SET 子句
        patch.setUserId(null);
        patch.setUserName(null);
        patch.setApiKeyHash(null);
        patch.setKeyPrefix(null);
        patch.setTenant(null);
    }

    private void requireRemark(String remark) {
        if (remark != null && remark.length() > 512) {
            throw badRequest("备注 remark 长度超出上限 512");
        }
    }

    private void requireFlag(String field, Integer value) {
        if (value != null && value != 0 && value != 1) {
            throw badRequest(field + " 只能为 0 或 1: " + value);
        }
    }

    private void requireExpiresAt(LocalDateTime expiresAt) {
        if (expiresAt != null && expiresAt.isBefore(LocalDateTime.now())) {
            throw badRequest("过期时间 expiresAt 不能早于当前时间");
        }
    }

    // ------------------------------------------------------------------ 工具

    private static String generateKey() {
        byte[] bytes = new byte[KEY_RANDOM_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return KEY_PREFIX + HexFormat.of().formatHex(bytes);
    }

    private static String prefixOf(String plain) {
        int len = KEY_PREFIX.length() + KEY_PREFIX_HEX;
        return plain.length() > len ? plain.substring(0, len) : plain;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static GatewayException forbidden(String message) {
        return new GatewayException(ErrorCode.FORBIDDEN, message);
    }
}
