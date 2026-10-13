package tech.qunxu.module.gateway.adapter;

import tech.qunxu.module.gateway.common.util.JwtHs256;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;

/**
 * 讯飞星火 WebSocket 鉴权 URL 签名工具（HMAC-SHA256）。
 *
 * <p>按讯飞开放平台规范，由 {@code host + date + request-line} 组装签名原文，以 {@code apiSecret} 做 HMAC-SHA256
 * 后 Base64 得到 {@code signature}；再组装 {@code authorization_origin} 并 Base64 得到 {@code authorization}，
 * 连同 {@code date、host} 作为查询参数追加到 WS 基础 URL。日期为 RFC1123 GMT 格式。</p>
 *
 * <p>纯函数、无状态，便于以固定时间戳做确定性单元测试。</p>
 */
public final class SparkSignature {

    private static final DateTimeFormatter RFC1123 = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneId.of("GMT"));

    private static final Base64.Encoder BASE64 = Base64.getEncoder();

    private SparkSignature() {
    }

    /** 格式化 RFC1123 GMT 日期（讯飞签名要求）。 */
    public static String formatDate(Instant instant) {
        return RFC1123.format(instant);
    }

    /**
     * 构建带签名的完整 WebSocket URL。
     *
     * @param baseWsUrl WS 基础地址，如 {@code wss://spark-api.xf-yun.com/v4.0/chat}
     * @param apiKey    讯飞 APIKey
     * @param apiSecret 讯飞 APISecret
     * @param instant   签名时间（用于 date 字段）
     */
    public static String buildUrl(String baseWsUrl, String apiKey, String apiSecret, Instant instant) {
        URI uri = URI.create(baseWsUrl);
        String host = uri.getHost();
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String date = formatDate(instant);
        String authorization = authorization(host, path, date, apiKey, apiSecret);
        String separator = baseWsUrl.contains("?") ? "&" : "?";
        return baseWsUrl + separator
                + "authorization=" + urlEncode(authorization)
                + "&date=" + urlEncode(date)
                + "&host=" + urlEncode(host);
    }

    /**
     * 计算 {@code authorization} 查询参数值（Base64 后的 authorization_origin）。
     *
     * <p>包可见以便测试直接校验签名中间量。</p>
     */
    static String authorization(String host, String path, String date, String apiKey, String apiSecret) {
        String signatureOrigin = "host: " + host + "\n"
                + "date: " + date + "\n"
                + "GET " + path + " HTTP/1.1";
        byte[] signatureSha = JwtHs256.hmacSha256(
                signatureOrigin.getBytes(StandardCharsets.UTF_8),
                apiSecret.getBytes(StandardCharsets.UTF_8));
        String signature = BASE64.encodeToString(signatureSha);
        String authorizationOrigin = "api_key=\"" + apiKey + "\", "
                + "algorithm=\"hmac-sha256\", "
                + "headers=\"host date request-line\", "
                + "signature=\"" + signature + "\"";
        return BASE64.encodeToString(authorizationOrigin.getBytes(StandardCharsets.UTF_8));
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
