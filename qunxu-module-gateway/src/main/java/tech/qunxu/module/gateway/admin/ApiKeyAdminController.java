package tech.qunxu.module.gateway.admin;

import tech.qunxu.module.gateway.admin.service.ApiKeyAdminService;
import tech.qunxu.module.gateway.persistence.entity.ApiKeyEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 网关 APIKey 管理接口（{@code /admin-api/gateway/api-keys}）。
 *
 * <p>权限模型（由 {@link ApiKeyAdminService} 强制）：</p>
 * <ul>
 *   <li><b>管理员</b>（super_admin 角色）：可查询/创建/修改/删除<b>所有用户</b>的 APIKey，
 *       创建时可指定 {@code userId} 或 {@code userName} 代他人创建；</li>
 *   <li><b>普通用户</b>：只能查询/管理<b>自己的</b> APIKey，创建时忽略任何归属参数。</li>
 * </ul>
 *
 * <p>安全约定：密钥明文（{@code apiKey} 字段）仅在 {@code POST}（创建）与
 * {@code POST /{id}/rotate}（轮换）响应中返回一次，其余接口只返回前缀与摘要相关元数据。</p>
 */
@RestController
@RequestMapping("/admin-api/gateway/api-keys")
public class ApiKeyAdminController {

    private final ApiKeyAdminService service;

    public ApiKeyAdminController(ApiKeyAdminService service) {
        this.service = service;
    }

    /** 分页查询 APIKey（管理员可看全部，普通用户仅自己的）。 */
    @GetMapping
    public Mono<AdminPage<ApiKeyEntity>> query(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return service.query(keyword, userId, status, page, size);
    }

    /** 列出全部 APIKey（不分页），归属规则同分页查询。 */
    @GetMapping("/all")
    public Mono<List<ApiKeyEntity>> queryAll(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer status) {
        return service.queryAll(keyword, userId, status);
    }

    /** 按 ID 查询单条（非所有者访问返回 403）。 */
    @GetMapping("/{id}")
    public Mono<ApiKeyEntity> get(@PathVariable Long id) {
        return service.getById(id);
    }

    /** 创建 APIKey：响应中的 {@code apiKey} 为明文，仅本次可见。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ApiKeyCreated> create(@RequestBody ApiKeyEntity body) {
        return service.createKey(body);
    }

    /** 部分更新（名称/备注/启用状态/过期时间；归属与密钥内容不可改）。 */
    @PutMapping("/{id}")
    public Mono<ApiKeyEntity> update(@PathVariable Long id, @RequestBody ApiKeyEntity body) {
        return service.update(id, body);
    }

    /** 逻辑删除（非所有者访问返回 403）。 */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable Long id) {
        return service.delete(id);
    }

    /** 轮换 APIKey：新明文仅本次响应可见，旧密钥立即失效。 */
    @PostMapping("/{id}/rotate")
    public Mono<ApiKeyCreated> rotate(@PathVariable Long id) {
        return service.rotate(id);
    }
}
