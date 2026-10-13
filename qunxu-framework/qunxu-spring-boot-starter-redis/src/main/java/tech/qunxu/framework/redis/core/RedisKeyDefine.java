package tech.qunxu.framework.redis.core;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Duration;

/**
 * Redis Key 定义。
 * <p>
 * 每个 Redis Key 通过该定义统一管理，避免散落各处的魔法字符串。
 */
@Getter
@AllArgsConstructor
public class RedisKeyDefine {

    /** Key 类型 */
    private final KeyTypeEnum keyType;

    /** Key 模板，支持 {} 占位符，例如 auth:token:{userId} */
    private final String keyTemplate;

    /** Key 超时时间 */
    private final Duration timeout;

    /** Key 的数据类型 */
    private final Class<?> valueClass;

    /**
     * Key 类型枚举
     */
    public enum KeyTypeEnum {
        /** 字符串 */
        STRING,
        /** 哈希 */
        HASH,
        /** 列表 */
        LIST,
        /** 集合 */
        SET,
        /** 有序集合 */
        ZSET
    }

}
