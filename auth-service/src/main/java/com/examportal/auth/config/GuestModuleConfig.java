package com.examportal.auth.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * B13a: the guest participants of paid open exams live in their own package, com.examportal.guest, next to the
 * accounts but apart from them: their own tables, tokens, security chain and endpoint registry
 * (GuestEndpointRegistryTest). The accounts' classes, paths and tables are not changed by it.
 */
@Configuration
@ComponentScan("com.examportal.guest")
public class GuestModuleConfig {
}
