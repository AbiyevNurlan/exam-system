package com.examportal.monitoring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

/**
 * Monitoring Service Application
 * Centralized monitoring, logging, and metrics aggregation service.
 */
@SpringBootApplication(scanBasePackages = {"com.examportal.monitoring", "com.examportal.common"})
@EnableR2dbcRepositories
public class MonitoringServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonitoringServiceApplication.class, args);
    }
}
