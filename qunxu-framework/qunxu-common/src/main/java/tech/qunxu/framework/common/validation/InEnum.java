package tech.qunxu.framework.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 枚举值校验注解。
 * <p>
 * 标注在字段上，校验字段值必须在指定枚举的值集合中。
 */
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE, ElementType.CONSTRUCTOR, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = InEnumValidator.class)
public @interface InEnum {

    /**
     * 指定枚举类，其必须实现 {@link tech.qunxu.framework.common.validation.IntArrayValuable}
     */
    Class<? extends IntArrayValuable> value();

    /**
     * 校验失败提示信息
     */
    String message() default "必须为指定值";

    /**
     * 分组
     */
    Class<?>[] groups() default {};

    /**
     * Payload
     */
    Class<? extends Payload>[] payload() default {};

}
