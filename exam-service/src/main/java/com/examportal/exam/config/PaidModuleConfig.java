package com.examportal.exam.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * B13b: paid open exams live in their own package, com.examportal.paid: their own tables (orders, payment_events,
 * entitlements), the guest's security chain, the payment providers and their endpoint registry
 * (PaidEndpointRegistryTest). The exams, audiences and sessions of today are used, not changed.
 */
@Configuration
@ComponentScan("com.examportal.paid")
public class PaidModuleConfig {
}
