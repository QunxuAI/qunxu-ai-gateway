package tech.qunxu.framework.security.core.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import tech.qunxu.framework.common.exception.enums.GlobalErrorCodeConstants;
import tech.qunxu.framework.common.pojo.CommonResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 无权限处理器。
 * <p>
 * 已登录但无权限访问接口时，返回 403 JSON 响应。
 */
@Slf4j
public class AccessDeniedHandlerImpl implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        log.debug("[handle][访问 URL({}) 时，用户无权限]", request.getRequestURI());
        CommonResult<?> result = CommonResult.error(GlobalErrorCodeConstants.FORBIDDEN);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(tech.qunxu.framework.common.util.json.JsonUtils.toJsonString(result));
    }

}
