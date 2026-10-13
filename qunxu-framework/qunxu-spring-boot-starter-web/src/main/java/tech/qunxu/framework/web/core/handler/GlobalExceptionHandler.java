package tech.qunxu.framework.web.core.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tech.qunxu.framework.common.exception.ErrorCode;
import tech.qunxu.framework.common.exception.ServiceException;
import tech.qunxu.framework.common.exception.enums.GlobalErrorCodeConstants;
import tech.qunxu.framework.common.pojo.CommonResult;
import tech.qunxu.framework.common.util.servlet.ServletUtils;
import tech.qunxu.framework.common.util.string.StrUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;

import java.util.Set;

/**
 * 全局异常处理器。
 * <p>
 * 将各类异常统一转换为 {@link CommonResult} 返回给前端：
 * 1. 业务异常 {@link ServiceException}：返回对应的错误码与提示信息；
 * 2. 参数校验异常：统一转换为 400 参数不正确；
 * 3. 权限异常：转换为 401 / 403；
 * 4. 其他异常：记录日志并返回 500 系统异常。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 业务异常
     */
    @ExceptionHandler(ServiceException.class)
    public CommonResult<?> serviceExceptionHandler(ServiceException ex) {
        return CommonResult.error(ex.getCode(), ex.getMessage());
    }

    /**
     * 处理 SpringMVC 请求参数缺失、类型错误等
     */
    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public CommonResult<?> missingRequestParameterExceptionHandler(Exception ex) {
        return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST);
    }

    /**
     * 处理 @RequestBody 参数校验异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public CommonResult<?> methodArgumentNotValidExceptionHandler(MethodArgumentNotValidException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String message = fieldError == null ? "请求参数不正确" : fieldError.getDefaultMessage();
        log.warn("[methodArgumentNotValidExceptionHandler]", ex);
        return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), message);
    }

    /**
     * 处理 @Valid 表单/查询参数校验异常
     */
    @ExceptionHandler(BindException.class)
    public CommonResult<?> bindExceptionHandler(BindException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String message = fieldError == null ? "请求参数不正确" : fieldError.getDefaultMessage();
        log.warn("[bindExceptionHandler]", ex);
        return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), message);
    }

    /**
     * 处理 @Validated 单参数校验异常
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public CommonResult<?> constraintViolationExceptionHandler(ConstraintViolationException ex) {
        Set<ConstraintViolation<?>> violations = ex.getConstraintViolations();
        String message = violations.stream().findFirst().map(ConstraintViolation::getMessage)
                .orElse("请求参数不正确");
        log.warn("[constraintViolationExceptionHandler]", ex);
        return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), message);
    }

    /**
     * 处理请求方法不支持异常
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public CommonResult<?> httpRequestMethodNotSupportedExceptionHandler(HttpRequestMethodNotSupportedException ex) {
        log.warn("[httpRequestMethodNotSupportedExceptionHandler]", ex);
        return CommonResult.error(405, String.format("请求方法不正确，正确方法为：%s", ex.getSupportedHttpMethods()));
    }

    /**
     * 处理 404 异常
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public CommonResult<?> noResourceFoundExceptionHandler(NoResourceFoundException ex) {
        log.warn("[noResourceFoundExceptionHandler]", ex);
        return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND);
    }

    /**
     * 处理未登录异常
     */
    @ExceptionHandler(AuthenticationException.class)
    public CommonResult<?> authenticationExceptionHandler(AuthenticationException ex) {
        log.warn("[authenticationExceptionHandler]", ex);
        return CommonResult.error(GlobalErrorCodeConstants.UNAUTHORIZED);
    }

    /**
     * 处理无权限异常
     */
    @ExceptionHandler(AccessDeniedException.class)
    public CommonResult<?> accessDeniedExceptionHandler(AccessDeniedException ex) {
        log.warn("[accessDeniedExceptionHandler]", ex);
        return CommonResult.error(GlobalErrorCodeConstants.FORBIDDEN);
    }

    /**
     * 处理其他未知异常
     */
    @ExceptionHandler(Exception.class)
    public CommonResult<?> defaultExceptionHandler(HttpServletRequest request, Exception ex) {
        log.error("[defaultExceptionHandler]", ex);
        // 返回 500 系统异常
        ErrorCode errorCode = GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR;
        if (StrUtils.isNotEmpty(ex.getMessage()) && ex.getMessage().length() < 100
                && !ServletUtils.isLocalAddress(ServletUtils.getClientIP(request))) {
            // 对外不暴露详细异常信息，仅本机调试时透出
            return CommonResult.error(errorCode.getCode(), errorCode.getMsg());
        }
        return CommonResult.error(errorCode);
    }

}
