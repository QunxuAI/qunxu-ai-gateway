package tech.qunxu.module.system.service.captcha;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import tech.qunxu.framework.common.exception.util.ServiceExceptionUtil;
import tech.qunxu.framework.common.util.security.SecurityUtils;
import tech.qunxu.module.system.enums.ErrorCodeConstants;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 验证码服务。
 * <p>
 * 生成四则运算算式验证码，答案缓存到 Redis，5 分钟内有效。
 */
@Service
@RequiredArgsConstructor
public class CaptchaService {

    /** 验证码 Key 前缀 */
    private static final String CAPTCHA_KEY = "captcha:";
    /** 验证码有效期 */
    private static final Duration CAPTCHA_TIMEOUT = Duration.ofMinutes(5);

    private final RedisTemplate<String, Object> redisTemplate;

    /** 是否开启验证码，默认开启 */
    @Value("${qunxu.captcha.enable:true}")
    private Boolean enable;

    /**
     * 生成验证码
     *
     * @return 验证码信息（uuid + 算式）
     */
    public CaptchaRespDTO generateCaptcha() {
        String uuid = SecurityUtils.generateToken();
        String expression = buildExpression();
        String answer = computeAnswer(expression);
        redisTemplate.opsForValue().set(CAPTCHA_KEY + uuid, answer, CAPTCHA_TIMEOUT);
        return new CaptchaRespDTO(uuid, expression);
    }

    /**
     * 校验验证码
     */
    public void validCaptcha(String uuid, String code) {
        // 关闭验证码时直接通过
        if (!enable) {
            return;
        }
        Object answer = redisTemplate.opsForValue().get(CAPTCHA_KEY + uuid);
        if (answer == null) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.AUTH_CAPTCHA_NOT_FOUND);
        }
        // 无论对错，验证码一次性使用
        redisTemplate.delete(CAPTCHA_KEY + uuid);
        if (code == null || !String.valueOf(answer).equals(code.trim())) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.AUTH_CAPTCHA_ERROR);
        }
    }

    /**
     * 生成简单四则运算算式
     */
    private String buildExpression() {
        int a = ThreadLocalRandom.current().nextInt(1, 10);
        int b = ThreadLocalRandom.current().nextInt(1, 10);
        int op = ThreadLocalRandom.current().nextInt(4);
        return switch (op) {
            case 0 -> a + " + " + b + " = ?";
            case 1 -> a + " - " + b + " = ?";
            case 2 -> a + " × " + b + " = ?";
            default -> {
                // 除法需要保证整除
                int divisor = ThreadLocalRandom.current().nextInt(1, 5);
                yield (a * divisor) + " ÷ " + divisor + " = ?";
            }
        };
    }

    /**
     * 计算算式答案
     */
    private String computeAnswer(String expression) {
        // 格式：a op b = ?
        String[] parts = expression.split(" ");
        int a = Integer.parseInt(parts[0]);
        int b = Integer.parseInt(parts[2]);
        String op = parts[1];
        return switch (op) {
            case "+" -> String.valueOf(a + b);
            case "-" -> String.valueOf(a - b);
            case "×" -> String.valueOf(a * b);
            default -> String.valueOf(a / b);
        };
    }

    /**
     * 验证码信息
     */
    public record CaptchaRespDTO(String uuid, String expression) {
    }

}
