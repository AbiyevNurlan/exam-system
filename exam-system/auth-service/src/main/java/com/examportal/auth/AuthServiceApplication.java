package com.examportal.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

/**
 * Authentication Service Application
 * Handles user authentication, authorization, and JWT token management.
 */
@SpringBootApplication(scanBasePackages = {"com.examportal.auth", "com.examportal.common"})
@EnableR2dbcRepositories
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
