package tech.qunxu.framework.mybatis.core.handler;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;

import java.time.LocalDateTime;

/**
 * 字段自动填充处理器。
 * <p>
 * 插入时自动填充创建时间、创建人；更新时自动填充更新时间、更新人。
 * 创建人/更新人默认取当前登录用户，未登录时为空字符串。
 */
public class DefaultDBFieldHandler implements MetaObjectHandler {

    /** 当前登录用户提供器：由上层模块（security）实现，避免本模块反向依赖 */
    private static volatile LoginUserProvider loginUserProvider;

    public DefaultDBFieldHandler() {
    }

    /**
     * 注册当前登录用户提供器
     */
    public static void setLoginUserProvider(LoginUserProvider provider) {
        loginUserProvider = provider;
    }

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        // 创建时间
        if (metaObject.hasSetter("createTime") && getFieldValByName("createTime", metaObject) == null) {
            setFieldValByName("createTime", now, metaObject);
        }
        // 更新时间
        if (metaObject.hasSetter("updateTime") && getFieldValByName("updateTime", metaObject) == null) {
            setFieldValByName("updateTime", now, metaObject);
        }
        // 模型网关实体使用 createdAt/updatedAt 命名，一并填充
        if (metaObject.hasSetter("createdAt") && getFieldValByName("createdAt", metaObject) == null) {
            setFieldValByName("createdAt", now, metaObject);
        }
        if (metaObject.hasSetter("updatedAt") && getFieldValByName("updatedAt", metaObject) == null) {
            setFieldValByName("updatedAt", now, metaObject);
        }
        // 创建人
        if (metaObject.hasSetter("creator") && getFieldValByName("creator", metaObject) == null) {
            setFieldValByName("creator", getLoginUserId(), metaObject);
        }
        // 更新人
        if (metaObject.hasSetter("updater") && getFieldValByName("updater", metaObject) == null) {
            setFieldValByName("updater", getLoginUserId(), metaObject);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        // 更新时间
        if (metaObject.hasSetter("updateTime")) {
            setFieldValByName("updateTime", LocalDateTime.now(), metaObject);
        }
        // 模型网关实体使用 updatedAt 命名，一并填充
        if (metaObject.hasSetter("updatedAt")) {
            setFieldValByName("updatedAt", LocalDateTime.now(), metaObject);
        }
        // 更新人
        if (metaObject.hasSetter("updater")) {
            setFieldValByName("updater", getLoginUserId(), metaObject);
        }
    }

    /**
     * 获取当前登录用户 ID 字符串，无登录用户时返回空字符串
     */
    private String getLoginUserId() {
        if (loginUserProvider != null) {
            Long userId = loginUserProvider.getLoginUserId();
            if (userId != null) {
                return String.valueOf(userId);
            }
        }
        return "";
    }

    /**
     * 当前登录用户 ID 提供器接口
     */
    public interface LoginUserProvider {

        /**
         * 获取当前登录用户 ID，未登录时返回 null
         */
        Long getLoginUserId();

    }

}
