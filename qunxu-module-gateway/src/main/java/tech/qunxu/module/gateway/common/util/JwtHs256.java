package tech.qunxu.module.gateway.common.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * 轻量 HS256（HMAC-SHA256）JWS 编码器：用于智谱 GLM 等需自签 JWT 的供应商鉴权。
 *
 * <p>不引入额外 JOSE 依赖，直接以 base64url(header).base64url(payload).base64url(signature) 组装紧凑序列化令牌。
 * 仅覆盖对称签名 HS256，密钥为供应商 API Secret 的 UTF-8 字节。</p>
 */
public final class JwtHs256 {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private JwtHs256() {
    }

    /**
     * 生成紧凑序列化的 HS256 JWS。
     *
     * @param headerJson  头部 JSON（如 {@code {"alg":"HS256","sign_type":"HMAC","typ":"JWT"}}）
     * @param payloadJson 载荷 JSON
     * @param secret      签名密钥字节
     * @return {@code header.payload.signature} 三段式令牌（各段 base64url 无填充）
     */
    public static String sign(String headerJson, String payloadJson, byte[] secret) {
        String headerPart = URL_ENCODER.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String payloadPart = URL_ENCODER.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = headerPart + "." + payloadPart;
        byte[] signature = hmacSha256(signingInput.getBytes(StandardCharsets.UTF_8), secret);
        return signingInput + "." + URL_ENCODER.encodeToString(signature);
    }

    /** 计算 HMAC-SHA256。 */
    public static byte[] hmacSha256(byte[] data, byte[] key) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 计算失败", e);
        }
    }

    /** base64url 解码（无填充），用于校验/解析令牌段。 */
    public static byte[] decode(String base64Url) {
        return Base64.getUrlDecoder().decode(base64Url);
    }
}
