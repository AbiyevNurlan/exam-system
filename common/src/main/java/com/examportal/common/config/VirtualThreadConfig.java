package com.examportal.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executors;

/**
 * Virtual Threads Configuration
 * Configures Java 21 Virtual Threads for high-performance concurrent operations.
 */
@Configuration
@EnableAsync
@ConditionalOnProperty(name = "spring.threads.virtual.enabled", havingValue = "true", matchIfMissing = true)
public class VirtualThreadConfig {

    /**
     * Creates an async task executor using virtual threads.
     * Virtual threads are lightweight and can handle millions of concurrent tasks.
     */
    @Bean
    public AsyncTaskExecutor applicationTaskExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * Creates a virtual thread executor for custom async operations.
     */
    @Bean(name = "virtualThreadExecutor")
    public AsyncTaskExecutor virtualThreadExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }
}
