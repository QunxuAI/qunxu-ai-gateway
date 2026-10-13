package tech.qunxu.framework.security.core.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tech.qunxu.framework.common.exception.enums.GlobalErrorCodeConstants;
import tech.qunxu.framework.common.pojo.CommonResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 认证失败处理器。
 * <p>
 * 未登录或 Token 失效访问受保护接口时，返回 401 JSON 响应。
 */
@Slf4j
public class AuthenticationEntryPointImpl implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        log.debug("[commence][访问 URL({}) 时，没有登录]", request.getRequestURI());
        CommonResult<?> result = CommonResult.error(GlobalErrorCodeConstants.UNAUTHORIZED);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(tech.qunxu.framework.common.util.json.JsonUtils.toJsonString(result));
    }

}
