package com.examportal.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.web.WebProperties;

/**
 * Gateway Configuration
 * Configures web resources and gateway-specific beans.
 */
@Configuration
public class GatewayConfig {

    @Bean
    public WebProperties.Resources resources() {
        return new WebProperties.Resources();
    }
}
