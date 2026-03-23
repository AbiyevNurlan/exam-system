package com.examportal.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis Configuration
 * Configures reactive Redis template for caching and pub/sub.
 * Only enabled when spring.data.redis.enabled=true
 */
@Configuration
@ConditionalOnProperty(name = "spring.data.redis.enabled", havingValue = "true", matchIfMissing = false)
public class RedisConfig {

    @Bean
    public ReactiveRedisTemplate<String, Object> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {
        
        StringRedisSerializer keySerializer = new StringRedisSerializer();
        Jackson2JsonRedisSerializer<Object> valueSerializer = 
                new Jackson2JsonRedisSerializer<>(Object.class);

        RedisSerializationContext.RedisSerializationContextBuilder<String, Object> builder =
                RedisSerializationContext.newSerializationContext(keySerializer);

        RedisSerializationContext<String, Object> context = builder
                .value(valueSerializer)
                .hashKey(keySerializer)
                .hashValue(valueSerializer)
                .build();

        return new ReactiveRedisTemplate<>(connectionFactory, context);
    }
}
