package com.examportal.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password Encoder Configuration
 * Configures BCrypt password encoder for secure password hashing.
 */
@Configuration
public class PasswordEncoderConfig {

    /**
     * BCrypt password encoder bean
     * Uses strength of 12 for strong password hashing
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
