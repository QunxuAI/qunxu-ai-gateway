package tech.qunxu.framework.common.util.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 安全工具类。
 * <p>
 * 提供密码加密（加盐 SHA-256）、随机字符串生成等能力，不依赖任何第三方框架。
 */
public final class SecurityUtils {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String SALT_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private SecurityUtils() {
    }

    /**
     * 生成随机盐值（16 位）
     */
    public static String generateSalt() {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(SALT_ALPHABET.charAt(RANDOM.nextInt(SALT_ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * 使用盐值对密码进行 SHA-256 加密，返回 Base64 编码结果
     */
    public static String encryptPassword(String rawPassword, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] combined = (rawPassword + salt).getBytes(StandardCharsets.UTF_8);
            // 迭代 1024 次，增加暴力破解成本
            byte[] hash = combined;
            for (int i = 0; i < 1024; i++) {
                hash = digest.digest(hash);
            }
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 校验密码是否正确
     */
    public static boolean matchesPassword(String rawPassword, String encodedPassword, String salt) {
        String target = encryptPassword(rawPassword, salt);
        return MessageDigest.isEqual(
                target.getBytes(StandardCharsets.UTF_8),
                encodedPassword.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成随机 Token（32 字节 Base64 编码，去除特殊字符）
     */
    public static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

}
