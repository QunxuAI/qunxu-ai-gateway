package tech.qunxu.framework.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

/**
 * {@link Mobile} 注解的校验器。
 * <p>
 * 支持中国大陆手机号（1 开头，第二位 3-9，共 11 位）。
 */
public class MobileValidator implements ConstraintValidator<Mobile, String> {

    private static final Pattern MOBILE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // 允许为 null，具体是否必填由 @NotNull 控制
        if (value == null || value.isEmpty()) {
            return true;
        }
        return MOBILE_PATTERN.matcher(value).matches();
    }

}
