package tech.qunxu.module.system.framework.operatelog.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志注解。
 * <p>
 * 标注在 Controller 方法上，自动记录操作日志（模块、操作名、入参、结果、耗时等）。
 * <pre>{@code
 * @OperateLog(module = "用户管理", name = "创建用户", type = "create")
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperateLog {

    /** 模块名 */
    String module();

    /** 操作名 */
    String name();

    /** 操作类型：create / update / delete / query / other */
    String type() default "other";

    /** 操作内容描述 */
    String content() default "";

}
