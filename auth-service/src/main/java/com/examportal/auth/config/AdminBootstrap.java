package com.examportal.auth.config;

import com.examportal.auth.repository.UserRepository;
import com.examportal.common.domain.entity.UserRole;
import com.examportal.common.utils.LogMaskUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Locale;

/**
 * Admin Bootstrap
 * Creates the first ADMIN from ADMIN_EMAIL / ADMIN_PASSWORD when no admin exists yet.
 * Self-registration only creates students, so this is the only way to get the first admin.
 * The password and the full email are never logged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    private static final String PLACEHOLDER = "CHANGE_ME";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${auth.bootstrap.admin-email:}")
    private String adminEmail;

    @Value("${auth.bootstrap.admin-password:}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (isUnset(adminEmail) || isUnset(adminPassword)) {
            log.info("Admin bootstrap skipped: ADMIN_EMAIL / ADMIN_PASSWORD are not set");
            return;
        }
        String email = adminEmail.trim().toLowerCase(Locale.ROOT);

        try {
            userRepository.existsByRole(UserRole.ADMIN)
                    .flatMap(adminExists -> {
                        if (adminExists) {
                            log.info("Admin bootstrap skipped: an ADMIN user already exists");
                            return Mono.empty();
                        }
                        // The insert re-checks "no admin yet" itself, so two instances starting together stay idempotent
                        return userRepository.insertFirstAdmin(email, passwordEncoder.encode(adminPassword))
                                .doOnNext(inserted -> {
                                    if (inserted > 0) {
                                        log.info("Admin bootstrap: created first ADMIN {}", LogMaskUtils.maskEmail(email));
                                    } else {
                                        log.warn("Admin bootstrap: no ADMIN created for {} (email already registered "
                                                + "or an admin was created concurrently)", LogMaskUtils.maskEmail(email));
                                    }
                                });
                    })
                    .block(TIMEOUT);
        } catch (RuntimeException e) {
            // Never let the bootstrap take the service down; the cause is logged without credentials
            log.error("Admin bootstrap failed: {}", e.getClass().getSimpleName());
        }
    }

    private boolean isUnset(String value) {
        return !StringUtils.hasText(value) || PLACEHOLDER.equals(value.trim());
    }
}
