package tech.qunxu.module.gateway.admin.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import tech.qunxu.module.gateway.admin.AdminPage;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.config.GatewayProperties;
import tech.qunxu.module.gateway.persistence.entity.BaseConfigEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 配置类资源管理服务的公共骨架：把「响应式卸载 + 校验 + 存在性判定 + 输出脱敏 + 变更后广播」
 * 收敛到一处，各资源服务只需声明 Mapper、资源名与校验规则。
 *
 * <p>底层为 MyBatis-Plus <b>阻塞 JDBC</b>，所有数据库操作统一经 {@link Schedulers#boundedElastic()}
 * 卸载，避免阻塞 Netty 事件循环。</p>
 *
 * <p>更新语义：入参实体的 {@code null} 字段不进入 SET 子句（MyBatis-Plus 默认
 * {@code FieldStrategy.NOT_NULL}），因此 PUT 天然支持<b>部分更新</b>；若实体带 {@code @Version}
 * 且入参携带 version，则自动启用乐观锁，冲突时返回 409。</p>
 *
 * @param <E> 继承 {@link BaseConfigEntity} 的配置实体
 */
public abstract class AbstractConfigAdminService<E extends BaseConfigEntity> {

    /** 敏感字段在响应中的占位值；更新请求回传该值表示「保持原值不变」。 */
    protected static final String MASKED = "***";

    private static final Logger log = LoggerFactory.getLogger(AbstractConfigAdminService.class);
    private static final int DEFAULT_PAGE_SIZE = 20;

    protected final GatewayProperties properties;
    protected final ObjectMapper objectMapper;

    protected AbstractConfigAdminService(GatewayProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 底层 Mapper。 */
    protected abstract BaseMapper<E> mapper();

    /** 资源中文名，用于异常消息与日志。 */
    protected abstract String resourceLabel();

    /** 新增前校验，不合法抛 {@link ErrorCode#BAD_REQUEST}。 */
    protected abstract void validateCreate(E entity);

    /** 更新前校验：{@code patch} 中仅含调用方显式提交的字段，需结合 {@code existing} 判断完整性。 */
    protected abstract void validateUpdate(E patch, E existing);

    /**
     * 新增前的写入准备钩子（默认无操作）：用于填充默认值、加密敏感字段等。
     * 在 {@link #validateCreate} 之后、insert 之前执行。
     */
    protected void prepareCreate(E entity) {
    }

    /**
     * 更新前的字段合并钩子。默认无操作；用于「敏感字段回传占位值时保留原值」等场景。
     *
     * @param patch    调用方提交的实体（已设置 id）
     * @param existing 数据库中的当前行
     */
    protected void mergeForUpdate(E patch, E existing) {
    }

    /** 删除前钩子：可做引用完整性检查，不满足时抛出异常阻止删除。 */
    protected void beforeDelete(E existing) {
    }

    /** 输出脱敏钩子：默认原样返回。 */
    protected E sanitize(E entity) {
        return entity;
    }

    /** 配置变更后的副作用钩子（默认无）。子类通常在此触发本地缓存失效与 Redis 广播。 */
    protected Mono<Void> afterChange(String action) {
        return Mono.empty();
    }

    /** 删除成功后的副作用钩子（默认无）。子类可在此清理 Redis 中的派生状态。 */
    protected Mono<Void> afterDelete(E existing) {
        return Mono.empty();
    }

    // ------------------------------------------------------------------ 查询

    /** 按 ID 查询单条，不存在抛 404。 */
    public Mono<E> getById(Long id) {
        return Mono.fromCallable(() -> sanitize(requireById(id)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 条件列表查询（不分页），结果已脱敏。调用方负责组装过滤与排序。 */
    public Mono<List<E>> list(Wrapper<E> wrapper) {
        return Mono.fromCallable(() -> {
                    List<E> rows = mapper().selectList(wrapper);
                    return rows == null ? List.<E>of() : rows.stream().map(this::sanitize).toList();
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 条件分页查询，结果已脱敏。页码从 1 起，页大小受 {@code gateway.admin.max-page-size} 封顶。 */
    public Mono<AdminPage<E>> page(Wrapper<E> wrapper, Integer page, Integer size) {
        int pageNo = normalizePage(page);
        int pageSize = normalizeSize(size);
        return Mono.fromCallable(() -> {
                    Page<E> result = mapper().selectPage(new Page<>(pageNo, pageSize), wrapper);
                    List<E> rows = result != null && result.getRecords() != null
                            ? result.getRecords() : List.of();
                    long total = result != null ? result.getTotal() : 0L;
                    return new AdminPage<>(rows.stream().map(this::sanitize).toList(), total, pageNo, pageSize);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 条件计数，供引用完整性检查等场景复用。仅可在 boundedElastic 线程内调用。 */
    protected long countSync(Wrapper<E> wrapper) {
        Long total = mapper().selectCount(wrapper);
        return total != null ? total : 0L;
    }

    // ------------------------------------------------------------------ 写入

    /** 新增一条配置。主键由数据库自增生成，返回落库后的实体（已脱敏）。 */
    public Mono<E> create(E entity) {
        if (entity == null) {
            return Mono.error(badRequest("请求体不能为空"));
        }
        return Mono.fromCallable(() -> {
                    entity.setId(null);
                    entity.setDeleted(null);
                    validateCreate(entity);
                    prepareCreate(entity);
                    mapper().insert(entity);
                    log.info("新增{}成功: id={}", resourceLabel(), entity.getId());
                    return entity;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(saved -> afterChange("create").thenReturn(saved))
                .map(this::sanitize);
    }

    /** 部分更新一条配置（{@code null} 字段保持原值）。不存在抛 404，乐观锁冲突抛 409。 */
    public Mono<E> update(Long id, E patch) {
        if (id == null) {
            return Mono.error(badRequest("缺少资源 ID"));
        }
        if (patch == null) {
            return Mono.error(badRequest("请求体不能为空"));
        }
        return Mono.fromCallable(() -> {
                    E existing = requireById(id);
                    patch.setId(id);
                    patch.setDeleted(null);
                    patch.setCreatedAt(null);
                    validateUpdate(patch, existing);
                    mergeForUpdate(patch, existing);
                    int rows = mapper().updateById(patch);
                    if (rows == 0) {
                        throw new GatewayException(ErrorCode.CONFLICT,
                                resourceLabel() + "更新失败（不存在或版本已变更）: id=" + id);
                    }
                    E fresh = mapper().selectById(id);
                    log.info("更新{}成功: id={}", resourceLabel(), id);
                    return fresh != null ? fresh : patch;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(saved -> afterChange("update").thenReturn(saved))
                .map(this::sanitize);
    }

    /** 逻辑删除一条配置。不存在抛 404；被引用时由 {@link #beforeDelete} 抛出异常阻止。 */
    public Mono<Void> delete(Long id) {
        if (id == null) {
            return Mono.error(badRequest("缺少资源 ID"));
        }
        return Mono.fromCallable(() -> {
                    E existing = requireById(id);
                    beforeDelete(existing);
                    mapper().deleteById(id);
                    log.info("删除{}成功: id={}", resourceLabel(), id);
                    return existing;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(existing -> afterChange("delete").then(afterDelete(existing)));
    }

    // ------------------------------------------------------------------ 工具

    /** 按 ID 取行，不存在抛 404。仅可在 boundedElastic 线程内调用。 */
    protected E requireById(Long id) {
        E entity = id != null ? mapper().selectById(id) : null;
        if (entity == null) {
            throw new GatewayException(ErrorCode.NOT_FOUND, resourceLabel() + "不存在: id=" + id);
        }
        return entity;
    }

    protected static GatewayException badRequest(String message) {
        return new GatewayException(ErrorCode.BAD_REQUEST, message);
    }

    protected static GatewayException conflict(String message) {
        return new GatewayException(ErrorCode.CONFLICT, message);
    }

    protected static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    protected static String trimmed(String s) {
        return s == null ? null : s.trim();
    }

    /** 校验字符串属于给定枚举取值集合（大小写不敏感），为空视为未提供而跳过。 */
    protected void requireOneOf(String field, String value, List<String> allowed) {
        if (!hasText(value)) {
            return;
        }
        String normalized = value.trim();
        for (String candidate : allowed) {
            if (candidate.equalsIgnoreCase(normalized)) {
                return;
            }
        }
        throw badRequest(field + " 取值非法: " + value + "，可选值 " + allowed);
    }

    /** 校验必填文本字段非空。 */
    protected String requireText(String field, String value, int maxLength) {
        if (!hasText(value)) {
            throw badRequest(field + " 不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw badRequest(field + " 长度超出上限 " + maxLength);
        }
        return normalized;
    }

    /** 校验 JSON 字段可解析（空值跳过）。 */
    protected void requireJson(String field, String value) {
        if (!hasText(value)) {
            return;
        }
        try {
            objectMapper.readTree(value);
        } catch (RuntimeException e) {
            throw badRequest(field + " 不是合法 JSON: " + e.getMessage());
        }
    }

    /** 页码归一：null/<=1 归为 1。 */
    protected int normalizePage(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    /** 页大小归一：null/<=0 归为默认值，超过上限则封顶。 */
    protected int normalizeSize(Integer size) {
        int max = properties.getAdmin().getMaxPageSize();
        if (max <= 0) {
            max = 500;
        }
        if (size == null || size <= 0) {
            return Math.min(DEFAULT_PAGE_SIZE, max);
        }
        return Math.min(size, max);
    }
}
