package tech.qunxu.framework.datapermission.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据权限注解。
 * <p>
 * 标注在 Service 方法上，方法执行期间对匹配的数据权限规则所涉及的表
 * 自动追加数据权限条件；使用 {@code enable = false} 可关闭当前方法的数据权限。
 * <pre>{@code
 * @DataPermission
 * public PageResult<XxxDO> selectPage(XxxPageReqVO reqVO) { ... }
 * }</pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DataPermission {

    /**
     * 是否开启数据权限，默认开启
     */
    boolean enable() default true;

}
