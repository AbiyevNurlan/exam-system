package com.examportal.guest.captcha;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * B13a item 7: the captcha of the guest code request when no provider is connected (P0-1 declares its own
 * GuestCaptcha bean, which replaces this one). guest.captcha.enabled=false (default): every request passes.
 * guest.captcha.enabled=true without a provider: every request is refused with 400 CAPTCHA_FAILED, and the start says why.
 */
@Slf4j
@Configuration
public class GuestCaptchaConfig {

    @Bean
    @ConditionalOnMissingBean(GuestCaptcha.class)
    public GuestCaptcha guestCaptcha(@Value("${guest.captcha.enabled:false}") boolean enabled) {
        if (!enabled) {
            return (token, clientIp) -> Mono.just(true);
        }
        log.warn("guest.captcha.enabled is true but no captcha provider is connected: every guest code request is refused");
        return (token, clientIp) -> Mono.just(false);
    }
}
