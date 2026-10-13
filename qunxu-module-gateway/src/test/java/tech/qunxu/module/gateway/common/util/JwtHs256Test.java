package tech.qunxu.module.gateway.common.util;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JwtHs256} 单元测试：验证紧凑序列化三段结构、base64url 段可逆、签名与独立 HMAC-SHA256 计算一致、密钥敏感与确定性。
 */
class JwtHs256Test {

    @Test
    void signProducesThreeReversibleBase64UrlSegments() {
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payload = "{\"sub\":\"tester\",\"exp\":1700000000}";
        byte[] secret = "test-secret".getBytes(StandardCharsets.UTF_8);

        String token = JwtHs256.sign(header, payload, secret);

        assertThat(token).isNotBlank();
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        // base64url 无填充
        assertThat(parts[0]).doesNotContain("=");
        assertThat(parts[1]).doesNotContain("=");

        Base64.Decoder decoder = Base64.getUrlDecoder();
        assertThat(new String(decoder.decode(parts[0]), StandardCharsets.UTF_8)).isEqualTo(header);
        assertThat(new String(decoder.decode(parts[1]), StandardCharsets.UTF_8)).isEqualTo(payload);
    }

    @Test
    void signatureMatchesIndependentHmacSha256() throws Exception {
        String header = "{\"alg\":\"HS256\"}";
        String payload = "{\"a\":1}";
        byte[] secret = "s3cr3t-key".getBytes(StandardCharsets.UTF_8);

        String token = JwtHs256.sign(header, payload, secret);
        String[] parts = token.split("\\.");

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        byte[] expected = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        String expectedSig = Base64.getUrlEncoder().withoutPadding().encodeToString(expected);

        assertThat(parts[2]).isEqualTo(expectedSig);
    }

    @Test
    void hmacSha256IsDeterministicKeySensitiveAnd32Bytes() {
        byte[] data = "host: example.com\ndate: now".getBytes(StandardCharsets.UTF_8);
        byte[] k1 = "key-1".getBytes(StandardCharsets.UTF_8);
        byte[] k2 = "key-2".getBytes(StandardCharsets.UTF_8);

        byte[] a = JwtHs256.hmacSha256(data, k1);
        byte[] b = JwtHs256.hmacSha256(data, k1);
        byte[] c = JwtHs256.hmacSha256(data, k2);

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a).hasSize(32);
    }

    @Test
    void differentSecretKeepsHeaderPayloadButChangesSignature() {
        String header = "{\"alg\":\"HS256\"}";
        String payload = "{\"a\":1}";
        String t1 = JwtHs256.sign(header, payload, "k1".getBytes(StandardCharsets.UTF_8));
        String t2 = JwtHs256.sign(header, payload, "k2".getBytes(StandardCharsets.UTF_8));

        assertThat(t1.substring(0, t1.lastIndexOf('.')))
                .isEqualTo(t2.substring(0, t2.lastIndexOf('.')));
        assertThat(t1).isNotEqualTo(t2);
    }
}
