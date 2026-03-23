package com.examportal.exam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Exam Service Application
 * Core microservice for exam management, questions, and results.
 */
@SpringBootApplication(scanBasePackages = {"com.examportal.exam", "com.examportal.common"})
@EnableR2dbcRepositories
@EnableScheduling
public class ExamServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExamServiceApplication.class, args);
    }
}
