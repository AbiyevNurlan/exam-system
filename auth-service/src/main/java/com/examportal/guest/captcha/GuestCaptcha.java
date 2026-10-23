package com.examportal.guest.captcha;

import reactor.core.publisher.Mono;

/**
 * B13a item 7: the hook of a captcha on POST /auth/guest/otp/request, where a code is sent by email to any address.
 * P0-1 connects a provider by declaring a bean of this type; until then GuestCaptchaConfig gives one that lets every
 * request through while guest.captcha.enabled is false (the default) and refuses every request when it is true
 * without a provider (fail closed: switching it on by mistake never opens anything).
 */
public interface GuestCaptcha {

    /**
     * Whether the captcha was passed. token: what the client sent (captchaToken, may be null); clientIp: the address
     * of the caller as far as known. Never logs the token.
     */
    Mono<Boolean> verify(String token, String clientIp);
}
