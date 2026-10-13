package tech.qunxu.framework.redis.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * Redis 自动配置。
 * <p>
 * 自定义 RedisTemplate 序列化：Key 使用 String 序列化，Value 使用 JSON 序列化，
 * 保证对象存储与读取的便捷性。Spring Data Redis 4 使用 Jackson 3，
 * {@link RedisSerializer#json()} 已原生支持 LocalDateTime 等 Java Time 类型。
 * <p>
 * 先于 Spring Boot 的 {@link DataRedisAutoConfiguration} 注册同名校 Bean，
 * 使 Boot 的默认 RedisTemplate（JDK 序列化）自动让位。
 */
@AutoConfiguration(before = DataRedisAutoConfiguration.class)
public class QunxuRedisAutoConfiguration {

    /**
     * 自定义 RedisTemplate Bean
     */
    @Bean
    @ConditionalOnMissingBean(name = "redisTemplate")
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // Key 序列化：String
        RedisSerializer<String> keySerializer = RedisSerializer.string();
        // Value 序列化：JSON
        RedisSerializer<Object> valueSerializer = RedisSerializer.json();

        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }

}
