package com.examportal.exam.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.time.Duration;

/**
 * Performance Optimization Configuration
 * Optimized connection pooling for 100,000 concurrent users.
 * 
 * Architecture:
 * - PostgreSQL: Uses Spring Boot's default R2DBC pool configured via application.yml
 * - Redis: 50 connections (25 core + 25 buffer) - only when enabled
 * - Virtual Threads: Unlimited (JVM managed)
 */
@Slf4j
@Configuration
public class PerformanceConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    // Note: R2DBC Connection Pool is now configured via application.yml
    // spring.r2dbc.pool.initial-size, max-size, etc.

    /**
     * Optimized Redis Connection Factory
     * Lettuce with pooling for high throughput caching
     * Only enabled when spring.data.redis.enabled=true
     */
    @Bean
    @ConditionalOnProperty(name = "spring.data.redis.enabled", havingValue = "true", matchIfMissing = false)
    public ReactiveRedisConnectionFactory reactiveRedisConnectionFactory() {
        GenericObjectPoolConfig<Object> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(50);        // Maximum 50 connections
        poolConfig.setMaxIdle(25);         // Keep 25 idle
        poolConfig.setMinIdle(10);         // Minimum 10 ready
        poolConfig.setMaxWait(Duration.ofSeconds(5));
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);
        poolConfig.setTestWhileIdle(true);

        LettucePoolingClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(5))
                .poolConfig(poolConfig)
                .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new org.springframework.data.redis.connection.RedisStandaloneConfiguration(redisHost, redisPort),
                clientConfig
        );
        
        factory.afterPropertiesSet();
        
        log.info("Redis Connection Pool initialized: max={}, idle={}", 50, 25);
        return factory;
    }

    /**
     * Custom ReactiveRedisTemplate with optimized serialization
     * Only enabled when spring.data.redis.enabled=true
     */
    @Bean
    @ConditionalOnProperty(name = "spring.data.redis.enabled", havingValue = "true", matchIfMissing = false)
    public ReactiveRedisTemplate<String, Object> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {
        
        Jackson2JsonRedisSerializer<Object> serializer = new Jackson2JsonRedisSerializer<>(Object.class);
        
        RedisSerializationContext<String, Object> context = RedisSerializationContext
                .<String, Object>newSerializationContext(new StringRedisSerializer())
                .value(serializer)
                .hashValue(serializer)
                .hashKey(new StringRedisSerializer())
                .build();

        return new ReactiveRedisTemplate<>(connectionFactory, context);
    }
}
