package tech.qunxu.module.gateway.adapter;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 讯飞星火 WS 签名工具单元测试：RFC1123 GMT 日期、签名 URL 追加 {@code authorization/date/host} 查询参数且确定性、
 * {@code authorization} 中间量可 Base64 还原并与独立 HMAC-SHA256 计算的 signature 一致、密钥敏感。
 */
class SparkSignatureTest {

    private static final Instant FIXED = Instant.parse("2024-01-15T08:30:00Z"); // 星期一
    private static final String BASE = "wss://spark-api.xf-yun.com/v4.0/chat";
    private static final String API_KEY = "testApiKey";
    private static final String API_SECRET = "testApiSecret";

    @Test
    void formatsRfc1123GmtDate() {
        assertThat(SparkSignature.formatDate(FIXED)).isEqualTo("Mon, 15 Jan 2024 08:30:00 GMT");
    }

    @Test
    void buildUrlAppendsAuthorizationDateHostQueryParams() {
        String url = SparkSignature.buildUrl(BASE, API_KEY, API_SECRET, FIXED);
        assertThat(url).startsWith(BASE + "?");
        assertThat(url).contains("authorization=");
        assertThat(url).contains("date=");
        assertThat(url).contains("host=spark-api.xf-yun.com");
    }

    @Test
    void buildUrlIsDeterministicForSameInstant() {
        String a = SparkSignature.buildUrl(BASE, API_KEY, API_SECRET, FIXED);
        String b = SparkSignature.buildUrl(BASE, API_KEY, API_SECRET, FIXED);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void authorizationDecodesToExpectedOriginAndIndependentSignature() throws Exception {
        String host = "spark-api.xf-yun.com";
        String path = "/v4.0/chat";
        String date = SparkSignature.formatDate(FIXED);

        String authorization = SparkSignature.authorization(host, path, date, API_KEY, API_SECRET);
        String origin = new String(Base64.getDecoder().decode(authorization), StandardCharsets.UTF_8);

        // 独立用 JDK Mac 重算 signature
        String signatureOrigin = "host: " + host + "\n" + "date: " + date + "\n" + "GET " + path + " HTTP/1.1";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(API_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getEncoder()
                .encodeToString(mac.doFinal(signatureOrigin.getBytes(StandardCharsets.UTF_8)));

        assertThat(origin).contains("api_key=\"" + API_KEY + "\"");
        assertThat(origin).contains("algorithm=\"hmac-sha256\"");
        assertThat(origin).contains("headers=\"host date request-line\"");
        assertThat(origin).contains("signature=\"" + signature + "\"");
    }

    @Test
    void differentSecretProducesDifferentAuthorization() {
        String date = SparkSignature.formatDate(FIXED);
        String a = SparkSignature.authorization("h", "/p", date, API_KEY, "secret-1");
        String b = SparkSignature.authorization("h", "/p", date, API_KEY, "secret-2");
        assertThat(a).isNotEqualTo(b);
    }
}
