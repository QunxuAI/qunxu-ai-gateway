package tech.qunxu.module.gateway.admin;

import tech.qunxu.module.gateway.admin.service.AbstractConfigAdminService;
import tech.qunxu.module.gateway.persistence.entity.BaseConfigEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import reactor.core.publisher.Mono;

/**
 * 配置类资源管理控制器的公共骨架：提供 {@code GET /{id}}、{@code POST}、{@code PUT /{id}}、
 * {@code DELETE /{id}} 四个语义一致的端点，各资源控制器只需声明类级 {@code @RequestMapping} 与列表查询。
 *
 * <p>泛型 {@code E} 由具体子类绑定，Spring 通过 {@code ResolvableType} 解析请求体类型，
 * 因此子类无需重复声明反序列化目标。</p>
 *
 * <p>语义约定：</p>
 * <ul>
 *   <li>新增返回 {@code 201 Created} + 落库实体；</li>
 *   <li>更新为<b>部分更新</b>——请求体中缺省（null）的字段保持原值，返回更新后的实体；</li>
 *   <li>删除为逻辑删除，返回 {@code 204 No Content}；</li>
 *   <li>资源不存在返回 {@code 404}，字段校验失败返回 {@code 400}，唯一约束/引用冲突返回 {@code 409}，
 *       均由 {@code GlobalErrorHandler} 统一渲染为 OpenAI 风格错误体。</li>
 * </ul>
 *
 * @param <E> 继承 {@link BaseConfigEntity} 的配置实体
 */
public abstract class AbstractConfigAdminController<E extends BaseConfigEntity> {

    protected final AbstractConfigAdminService<E> service;

    protected AbstractConfigAdminController(AbstractConfigAdminService<E> service) {
        this.service = service;
    }

    /** 按 ID 查询单条。 */
    @GetMapping("/{id}")
    public Mono<E> get(@PathVariable Long id) {
        return service.getById(id);
    }

    /** 新增一条配置。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<E> create(@RequestBody E body) {
        return service.create(body);
    }

    /** 部分更新一条配置（null 字段保持原值）。 */
    @PutMapping("/{id}")
    public Mono<E> update(@PathVariable Long id, @RequestBody E body) {
        return service.update(id, body);
    }

    /** 逻辑删除一条配置。 */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable Long id) {
        return service.delete(id);
    }
}
