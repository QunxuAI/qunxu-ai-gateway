package tech.qunxu.module.gateway.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 系统用户只读摘要查询（{@code system_users}）。
 *
 * <p>网关模块与 system 模块同库部署；管理员为「其他用户」创建 APIKey 时，需要把
 * 用户名解析为真实的 {@code userId} 并记录所有者昵称/租户。这里只做<b>只读</b>的
 * 单行查询，不参与任何写操作，避免网关模块与 system 模块产生双向依赖。</p>
 *
 * <p>注意：{@code system_users} 无对应的网关实体，MyBatis-Plus 租户拦截器按
 * {@code ignoreTable} 规则不会给该语句附加租户过滤（跨租户查询由调用方按需过滤）。</p>
 */
@Mapper
public interface SystemUserBriefMapper {

    /** 用户摘要（只读快照字段，供 APIKey 归属展示与租户解析）。 */
    final class UserBrief {
        private Long id;
        private String username;
        private String nickname;
        private Long tenantId;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getNickname() { return nickname; }
        public void setNickname(String nickname) { this.nickname = nickname; }
        public Long getTenantId() { return tenantId; }
        public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    }

    /** 按用户 ID 查询未删除用户摘要。 */
    @Select("SELECT id, username, nickname, tenant_id AS tenantId FROM system_users WHERE id = #{id} AND deleted = 0")
    UserBrief selectById(@Param("id") Long id);

    /** 按用户名查询未删除用户摘要（多租户同名时优先同租户、否则取最早一条）。 */
    @Select("""
            SELECT id, username, nickname, tenant_id AS tenantId
            FROM system_users
            WHERE username = #{username} AND deleted = 0
            ORDER BY (tenant_id = #{tenantId}) DESC, id
            LIMIT 1
            """)
    UserBrief selectByUsername(@Param("username") String username, @Param("tenantId") Long tenantId);
}
