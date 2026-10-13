package tech.qunxu.module.gateway.adapter;

import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.concurrent.TimeoutException;

/**
 * 适配器错误归一化工具：把下游 HTTP/连接/超时异常映射为携带 {@link ErrorCode} 的 {@link GatewayException}。
 *
 * <p>统一区分凭证过期（401/403 → {@link ErrorCode#AUTH_EXPIRED}）、限流（429）、超时（408 或读超时）与一般供应商错误，
 * 供 DashScope 原生、讯飞 WS 等适配器复用；OpenAI 兼容适配器保留其等价实现。已是 {@link GatewayException} 者原样返回。</p>
 */
public final class AdapterErrorMapper {

    private static final int MAX_DETAIL = 512;

    private AdapterErrorMapper() {
    }

    /** 归一化下游调用异常。{@code provider} 为供应商编码，仅用于错误消息。 */
    public static GatewayException map(Throwable e, String provider) {
        if (e instanceof GatewayException ge) {
            return ge;
        }
        if (e instanceof WebClientResponseException wre) {
            int status = wre.getStatusCode().value();
            String detail = truncate(wre.getResponseBodyAsString());
            ErrorCode code = switch (status) {
                case 401, 403 -> ErrorCode.AUTH_EXPIRED;
                case 429 -> ErrorCode.RATE_LIMITED;
                case 408 -> ErrorCode.PROVIDER_TIMEOUT;
                default -> ErrorCode.PROVIDER_ERROR;
            };
            return new GatewayException(code, "下游返回 HTTP " + status + " (" + provider + ")", detail, e);
        }
        if (isTimeout(e)) {
            return new GatewayException(ErrorCode.PROVIDER_TIMEOUT, "下游响应超时 (" + provider + ")", e);
        }
        if (e instanceof WebClientRequestException) {
            return new GatewayException(ErrorCode.PROVIDER_ERROR, "下游连接失败 (" + provider + ")", e);
        }
        return new GatewayException(ErrorCode.PROVIDER_ERROR, "下游调用失败 (" + provider + ")", e);
    }

    /** 是否为超时类异常（含嵌套 cause）。 */
    public static boolean isTimeout(Throwable e) {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth++ < 8) {
            if (cur instanceof TimeoutException || cur.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_DETAIL ? s : s.substring(0, MAX_DETAIL) + "...";
    }
}
