package tech.qunxu.framework.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Arrays;

/**
 * {@link InEnum} 注解的校验器。
 */
public class InEnumValidator implements ConstraintValidator<InEnum, Integer> {

    /** 合法值集合 */
    private int[] values;

    @Override
    public void initialize(InEnum annotation) {
        this.values = annotation.value().getEnumConstants()[0].array();
    }

    @Override
    public boolean isValid(Integer value, ConstraintValidatorContext context) {
        // 允许为 null，具体是否必填由 @NotNull 控制
        if (value == null) {
            return true;
        }
        return Arrays.stream(values).anyMatch(item -> item == value);
    }

}
