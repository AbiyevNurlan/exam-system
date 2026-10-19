package com.examportal.exam.service;

import com.examportal.common.domain.dto.AuditEntryResponse;
import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.participant.ParticipantCardDtos;
import com.examportal.common.exception.AccessDeniedException;
import com.examportal.common.exception.ResourceNotFoundException;
import com.examportal.exam.client.UserDirectoryClient;
import com.examportal.exam.repository.MyStatsRepository;
import com.examportal.exam.repository.ParticipantCardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Participant Card Service (B11)
 * GET /api/admin/participants/{id}: one participant on one screen.
 *
 * Who sees what (also enforced by path in SecurityConfig):
 *   ADMIN       every account, every group, every attempt, the statistics over every exam, the newest events
 *   INSTRUCTOR  (B13: without phone, lock state and profile) only a member of a group the instructor owns (anyone else is a 404, like an unknown id, so an
 *               instructor cannot even learn that an account exists); of that member only the instructor's own
 *               groups and the attempts at exams the instructor created, statistics over those exams; no events
 *   anyone else 403
 *
 * A fixed number of statements, all by the user's id, run at once: the account part from auth-service (one
 * internal call), groups, the first page of attempts and its count, the statistics (the rule of
 * GET /api/me/stats), the newest group/exam events; names and codes of the events in one lookup.
 */
@Service
@RequiredArgsConstructor
public class ParticipantCardService {

    private static final String ADMIN = "ADMIN";
    private static final String INSTRUCTOR = "INSTRUCTOR";

    private final UserDirectoryClient userDirectory;
    private final ParticipantCardRepository cardRepository;
    private final MyStatsRepository statsRepository;
    private final AuditService auditService;

    public Mono<ParticipantCardDtos.Card> card(UUID userId, UUID callerId, String callerRole) {
        return scope(userId, callerId, callerRole).flatMap(owner -> {
            boolean admin = owner.isEmpty();
            UUID examOwner = admin ? null : owner.get();
            int events = admin ? ParticipantCardDtos.EVENTS : 0;
            Mono<List<com.examportal.exam.repository.AuditQueryRepository.Row>> examEvents = admin
                    ? cardRepository.findEvents(userId, events) : Mono.just(List.of());
            return Mono.zip(
                            userDirectory.participantCard(userId, events),
                            cardRepository.findGroups(userId, examOwner),
                            sessions(userId, examOwner, 0, ParticipantCardDtos.DEFAULT_SESSIONS),
                            statsRepository.forUser(userId, examOwner, LocalDateTime.now()),
                            examEvents)
                    .flatMap(found -> {
                        ParticipantCardDtos.AuthCard account = found.getT1();
                        Mono<List<AuditEntryResponse>> recent = admin
                                ? auditService.merge(found.getT5(), account.getAccountEvents(), ParticipantCardDtos.EVENTS)
                                : Mono.just(List.of());
                        return recent.map(merged -> ParticipantCardDtos.Card.builder()
                                .id(account.getId())
                                .participantCode(account.getParticipantCode())
                                .firstName(account.getFirstName())
                                .lastName(account.getLastName())
                                .email(account.getEmail())
                                // B13: a teacher sees name, code, email, the own groups and the own exams only; the
                                // phone, the lock state and the profile (birth date, workplace, institution, category ...)
                                // are dropped here, on the server
                                .phoneNumber(admin ? account.getPhoneNumber() : null)
                                .role(account.getRole())
                                .registeredAt(account.getRegisteredAt())
                                .emailVerified(account.isEmailVerified())
                                .active(account.isActive())
                                .blocked(admin && account.isBlocked())
                                .lockedUntil(admin ? account.getLockedUntil() : null)
                                .profile(admin ? account.getProfile() : null)
                                .scope(admin ? ParticipantCardDtos.SCOPE_ALL : ParticipantCardDtos.SCOPE_OWN)
                                .groups(found.getT2())
                                .sessions(found.getT3())
                                .stats(found.getT4())
                                .recentEvents(merged)
                                .build());
                    });
        });
    }

    /**
     * More attempts of the card, newest first (size at most 50), under the same rule of who sees what
     */
    public Mono<PageResponse<ParticipantCardDtos.SessionRow>> sessionsPage(UUID userId, UUID callerId, String callerRole,
                                                                          int page, int size) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), ParticipantCardDtos.MAX_SESSIONS);
        return scope(userId, callerId, callerRole)
                .flatMap(owner -> sessions(userId, owner.orElse(null), pageNumber, pageSize));
    }

    /**
     * Empty Optional: everything (ADMIN); the instructor's id: only the own groups and exams. A participant who is
     * not in a group of the instructor is a 404 (as if the id did not exist); any other role a 403.
     */
    Mono<java.util.Optional<UUID>> scope(UUID userId, UUID callerId, String callerRole) {
        if (ADMIN.equals(callerRole)) {
            return Mono.just(java.util.Optional.empty());
        }
        if (INSTRUCTOR.equals(callerRole) && callerId != null) {
            return cardRepository.isMemberOfGroupOwnedBy(userId, callerId)
                    .flatMap(member -> member
                            ? Mono.just(java.util.Optional.of(callerId))
                            : Mono.error(new ResourceNotFoundException("Participant", String.valueOf(userId))));
        }
        return Mono.error(new AccessDeniedException("Only administrators and instructors can see participant cards"));
    }

    private Mono<PageResponse<ParticipantCardDtos.SessionRow>> sessions(UUID userId, UUID owner, int page, int size) {
        return Mono.zip(cardRepository.findSessions(userId, owner, page, size), cardRepository.countSessions(userId, owner))
                .map(found -> PageResponse.of(found.getT1(), page, size, found.getT2()));
    }
}
