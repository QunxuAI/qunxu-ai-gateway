package tech.qunxu.framework.redis.core;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Redis Key 工具类。
 * <p>
 * 根据 Key 模板与参数拼接完整的 Redis Key。
 */
public final class RedisKeyUtils {

    private RedisKeyUtils() {
    }

    /**
     * 拼接 Redis Key：将模板中的 {} 占位符替换为参数
     *
     * @param keyTemplate Key 模板，如 auth:token:{userId}
     * @param params      占位符参数
     * @return 完整 Key
     */
    public static String formatKey(String keyTemplate, Object... params) {
        if (params == null || params.length == 0) {
            return keyTemplate;
        }
        String result = keyTemplate;
        for (Object param : params) {
            int start = result.indexOf('{');
            int end = result.indexOf('}', start);
            if (start == -1 || end == -1) {
                break;
            }
            result = result.substring(0, start) + param + result.substring(end + 1);
        }
        return result;
    }

    /**
     * 构建带前缀的 Key，例如添加模块前缀
     */
    public static String buildKey(String prefix, Object... parts) {
        String joined = Arrays.stream(parts)
                .map(String::valueOf)
                .collect(Collectors.joining(":"));
        return prefix + ":" + joined;
    }

}
