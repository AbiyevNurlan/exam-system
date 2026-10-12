package com.examportal.exam.service;

import com.examportal.exam.repository.GroupImportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.UUID;

/**
 * Group Invite Service
 * Applies pending CSV invites to a user. The caller (auth-service, through the internal endpoint)
 * guarantees that the email belongs to the user and is VERIFIED; this service cannot check that.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupInviteService {

    private final GroupImportRepository importRepository;

    /**
     * Returns the number of groups joined; 0 when no invite was waiting. Safe to call on every login.
     */
    public Mono<Long> applyPendingInvites(UUID userId, String email) {
        return importRepository.applyPendingInvites(userId, email.trim().toLowerCase(Locale.ROOT))
                .doOnNext(applied -> {
                    if (applied > 0) {
                        log.info("Applied {} pending group invite(s) for user {}", applied, userId);
                    }
                });
    }
}