package com.examportal.exam.config;

import com.examportal.common.config.UtcDateTimeModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;

/**
 * UTC JSON Configuration
 * Every date-time this service sends is ISO-8601 UTC with "Z" (2026-10-13T07:59:31.099Z), and
 * every one it receives is read as UTC unless it carries its own offset. See UtcDateTimeModule.
 */
@Configuration
public class UtcJsonConfig {

    public UtcJsonConfig(ObjectMapper objectMapper) {
        objectMapper.registerModule(new UtcDateTimeModule());
    }
}