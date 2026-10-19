package com.examportal.auth.service;

import com.examportal.auth.client.InstructorFiguresClient;
import com.examportal.auth.exception.DuplicateEmailException;
import com.examportal.auth.repository.InstructorQueryRepository;
import com.examportal.auth.repository.UserRepository;
import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.instructor.InstructorDtos;
import com.examportal.common.domain.entity.User;
import com.examportal.common.domain.entity.UserRole;
import com.examportal.common.exception.ApplicationException;
import com.examportal.common.exception.ValidationException;
import com.examportal.common.security.TokenRevocationStore;
import com.examportal.common.utils.LogMaskUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Instructor Administration (B13), for ADMIN only (the controller and the path rule enforce it).
 *
 * A teacher is made by an administrator, never by the open registration: an INSTRUCTOR account, active, its email
 * marked verified (no code is sent), with a temporary password the system makes (16 characters, SecureRandom). That
 * password is answered once, in the answer of the create or the reset, and nowhere else: only its hash is stored, and
 * it is never logged, audited, cached or put into an error. Until the teacher sets an own password the tokens reach
 * only the password change and the sign-out (must_change_password, JwtAuthenticationFilter); the temporary password
 * works for TEMPORARY_PASSWORD_DAYS.
 *
 * Deactivating ends every session at once (refresh tokens in the database, access tokens through
 * TokenRevocationStore) and keeps everything the teacher made. A teacher with exams that are ACTIVE or SCHEDULED is
 * deactivated only with confirm=true (409 INSTRUCTOR_HAS_ACTIVE_EXAMS otherwise; also when exam-service cannot tell).
 * Every action writes an admin_audit_log row with the administrator and the teacher, never a password.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstructorAdminService {

    static final int TEMPORARY_PASSWORD_DAYS = 7;
    static final String INSTRUCTOR_CREATED = "INSTRUCTOR_CREATED";
    static final String INSTRUCTOR_UPDATED = "INSTRUCTOR_UPDATED";
    static final String INSTRUCTOR_DEACTIVATED = "INSTRUCTOR_DEACTIVATED";
    static final String INSTRUCTOR_ACTIVATED = "INSTRUCTOR_ACTIVATED";
    static final String INSTRUCTOR_PASSWORD_RESET = "INSTRUCTOR_PASSWORD_RESET";
    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final InstructorQueryRepository queryRepository;
    private final TokenService tokenService;
    private final TokenRevocationStore tokenRevocationStore;
    private final InstructorFiguresClient figuresClient;
    private final PasswordEncoder passwordEncoder;
    private final AccountAuditLog auditLog;
    private final TransactionalOperator transactionalOperator;

    /**
     * A new teacher with a temporary password (answered once). The same email as any existing account is 409
     * EMAIL_ALREADY_EXISTS, with nothing about that account.
     */
    public Mono<InstructorDtos.Created> create(InstructorDtos.CreateRequest request, UUID adminId) {
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        String firstName = request.getFirstName().trim();
        String lastName = request.getLastName().trim();
        if (firstName.length() < 2 || lastName.length() < 2) {
            return Mono.error(new ValidationException("First and last name must have at least 2 characters"));
        }
        String temporary = TemporaryPasswords.generate();
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(TEMPORARY_PASSWORD_DAYS);
        LocalDateTime now = LocalDateTime.now();
        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(temporary))
                .firstName(firstName)
                .lastName(lastName)
                .role(UserRole.INSTRUCTOR)
                .emailVerified(true)
                .accountLocked(false)
                .failedLoginAttempts(0)
                .mustChangePassword(true)
                .tempPasswordExpiresAt(expiresAt)
                .active(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        Mono<User> creation = userRepository.existsByEmail(email)
                .flatMap(exists -> exists ? Mono.<User>error(new DuplicateEmailException(email)) : userRepository.save(user))
                .flatMap(saved -> auditLog.record(adminId, INSTRUCTOR_CREATED, saved.getId(), "email=" + LogMaskUtils.maskEmail(email))
                        .thenReturn(saved));
        return transactionalOperator.transactional(creation)
                // A concurrent create of the same email meets the unique index
                .onErrorMap(DuplicateKeyException.class, e -> new DuplicateEmailException(email))
                // The participant code is given by the database: read the row back
                .flatMap(saved -> userRepository.findById(saved.getId()))
                .map(saved -> InstructorDtos.Created.builder()
                        .id(saved.getId())
                        .participantCode(saved.getParticipantCode())
                        .firstName(saved.getFirstName())
                        .lastName(saved.getLastName())
                        .email(saved.getEmail())
                        .role(UserRole.INSTRUCTOR.name())
                        .active(true)
                        .mustChangePassword(true)
                        .temporaryPassword(temporary)
                        .temporaryPasswordExpiresAt(expiresAt)
                        .build())
                .doOnSuccess(created -> log.info("Teacher {} created by administrator {}", created.getId(), adminId));
    }

    /**
     * One page of teachers, newest first, with what each owns in exam-service (null when it cannot be asked)
     */
    public Mono<PageResponse<InstructorDtos.Row>> list(Boolean active, String q, int page, int size) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        String term = q == null || q.isBlank() ? null : q.trim();
        if (term != null && (term.length() < 2 || term.length() > 100)) {
            return Mono.error(new ValidationException("q", "Search text must be between 2 and 100 characters"));
        }
        return Mono.zip(queryRepository.findPage(active, term, pageSize, (long) pageNumber * pageSize).collectList(),
                        queryRepository.count(active, term))
                .flatMap(found -> figuresClient.figures(found.getT1().stream().map(InstructorQueryRepository.Row::id).toList())
                        .map(figures -> PageResponse.of(found.getT1().stream().map(row -> toRow(row, figures)).toList(),
                                pageNumber, pageSize, found.getT2())));
    }

    /**
     * Name and active state. Deactivating: every session ends at once (the access tokens first, so a failure leaves
     * everything as it was), the content stays; with ACTIVE or SCHEDULED exams only with confirm.
     */
    public Mono<InstructorDtos.Row> update(UUID instructorId, InstructorDtos.UpdateRequest request, boolean confirm, UUID adminId) {
        return userRepository.findInstructor(instructorId)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(current -> {
                    String firstName = request.getFirstName() != null ? request.getFirstName().trim() : current.getFirstName();
                    String lastName = request.getLastName() != null ? request.getLastName().trim() : current.getLastName();
                    if (firstName.length() < 2 || lastName.length() < 2) {
                        return Mono.error(new ValidationException("First and last name must have at least 2 characters"));
                    }
                    boolean active = request.getActive() != null ? request.getActive() : current.isActive();
                    boolean deactivating = current.isActive() && !active;
                    Mono<Void> guard = deactivating && !confirm
                            ? Mono.defer(() -> requireNoActiveExams(instructorId)) : Mono.empty();
                    Mono<Void> revokeFirst = deactivating ? Mono.defer(() -> revoke(instructorId)) : Mono.empty();
                    Mono<Void> write = userRepository.findInstructorForUpdate(instructorId)
                            .switchIfEmpty(Mono.error(notFound()))
                            .flatMap(locked -> userRepository.updateInstructor(instructorId, firstName, lastName, active))
                            .then(deactivating ? Mono.defer(() -> tokenService.revokeAllUserTokens(instructorId)) : Mono.empty())
                            .then(auditLog.record(adminId, action(current, active), instructorId,
                                    changes(current, firstName, lastName, active)));
                    return guard.then(revokeFirst).then(Mono.defer(() -> transactionalOperator.transactional(write)));
                })
                .then(Mono.defer(() -> row(instructorId)))
                .doOnSuccess(row -> log.info("Teacher {} changed by administrator {} (active={})", instructorId, adminId,
                        row.isActive()));
    }

    /**
     * A new temporary password (answered once): the flag is set again, every session ends at once, the 7 days start
     * again. A lock from failed sign-ins stays (POST /auth/admin/users/{id}/unlock lifts it).
     */
    public Mono<InstructorDtos.Created> resetPassword(UUID instructorId, UUID adminId) {
        String temporary = TemporaryPasswords.generate();
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(TEMPORARY_PASSWORD_DAYS);
        return userRepository.findInstructor(instructorId)
                .switchIfEmpty(Mono.error(notFound()))
                // The access tokens first: when that fails nothing has changed and the administrator is told
                .flatMap(found -> revoke(instructorId).thenReturn(found))
                .flatMap(found -> transactionalOperator.transactional(
                                userRepository.setTemporaryPassword(instructorId, passwordEncoder.encode(temporary), expiresAt)
                                        .then(tokenService.revokeAllUserTokens(instructorId))
                                        .then(auditLog.record(adminId, INSTRUCTOR_PASSWORD_RESET, instructorId, null)))
                        .thenReturn(found))
                .map(found -> InstructorDtos.Created.builder()
                        .id(found.getId())
                        .participantCode(found.getParticipantCode())
                        .firstName(found.getFirstName())
                        .lastName(found.getLastName())
                        .email(found.getEmail())
                        .role(UserRole.INSTRUCTOR.name())
                        .active(found.isActive())
                        .mustChangePassword(true)
                        .temporaryPassword(temporary)
                        .temporaryPasswordExpiresAt(expiresAt)
                        .build())
                .doOnSuccess(reset -> log.info("Temporary password of teacher {} reset by administrator {}", instructorId, adminId));
    }

    // ==================== Private Helper Methods ====================

    private Mono<Void> requireNoActiveExams(UUID instructorId) {
        return figuresClient.figures(List.of(instructorId)).flatMap(figures -> {
            Long running = figures.map(all -> all.get(instructorId)).map(InstructorDtos.Figures::getActiveExamCount).orElse(null);
            if (running != null && running == 0) {
                return Mono.empty();
            }
            return Mono.error(new ApplicationException(running == null
                    ? "Whether the teacher has running exams cannot be checked now; repeat with confirm=true to deactivate anyway"
                    : "The teacher has " + running + " exams that are active or scheduled; repeat with confirm=true to deactivate anyway",
                    HttpStatus.CONFLICT, "INSTRUCTOR_HAS_ACTIVE_EXAMS")
                    .withDetails(running == null ? Map.of("activeExamCount", "unknown") : Map.of("activeExamCount", running)));
        });
    }

    private Mono<Void> revoke(UUID instructorId) {
        return tokenRevocationStore.revokeAllIssuedBefore(instructorId, Instant.now())
                .onErrorMap(e -> !(e instanceof ApplicationException), e -> new ApplicationException(
                        "The sessions of the teacher cannot be ended now (Redis is not reachable); nothing was changed",
                        HttpStatus.SERVICE_UNAVAILABLE, "TOKEN_REVOCATION_UNAVAILABLE"));
    }

    private Mono<InstructorDtos.Row> row(UUID instructorId) {
        return userRepository.findInstructor(instructorId)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(user -> figuresClient.figures(List.of(instructorId))
                        .map(figures -> toRow(new InstructorQueryRepository.Row(user.getId(), user.getParticipantCode(),
                                user.getFirstName(), user.getLastName(), user.getEmail(), user.isActive(),
                                user.isMustChangePassword(), user.getTempPasswordExpiresAt(), user.getCreatedAt(),
                                user.getLastLoginAt()), figures)));
    }

    private static InstructorDtos.Row toRow(InstructorQueryRepository.Row row, Optional<Map<UUID, InstructorDtos.Figures>> figures) {
        InstructorDtos.Figures own = figures.map(all -> all.get(row.id())).orElse(null);
        boolean available = figures.isPresent();
        String status = !row.active() ? "DEACTIVATED" : row.mustChangePassword() ? "PASSWORD_CHANGE_REQUIRED" : "ACTIVE";
        return InstructorDtos.Row.builder()
                .id(row.id())
                .participantCode(row.participantCode())
                .firstName(row.firstName())
                .lastName(row.lastName())
                .email(row.email())
                .status(status)
                .active(row.active())
                .mustChangePassword(row.mustChangePassword())
                .temporaryPasswordExpiresAt(row.temporaryPasswordExpiresAt())
                .createdAt(row.createdAt())
                .lastLoginAt(row.lastLoginAt())
                .groupCount(available ? (own != null ? own.getGroupCount() : 0L) : null)
                .memberCount(available ? (own != null ? own.getMemberCount() : 0L) : null)
                .examCount(available ? (own != null ? own.getExamCount() : 0L) : null)
                .activeExamCount(available ? (own != null ? own.getActiveExamCount() : 0L) : null)
                .figuresAvailable(available)
                .build();
    }

    private static String action(User current, boolean active) {
        if (current.isActive() && !active) {
            return INSTRUCTOR_DEACTIVATED;
        }
        if (!current.isActive() && active) {
            return INSTRUCTOR_ACTIVATED;
        }
        return INSTRUCTOR_UPDATED;
    }

    // "field: before -> after" for what changed; names only, never a password
    private static String changes(User current, String firstName, String lastName, boolean active) {
        StringBuilder text = new StringBuilder();
        if (!firstName.equals(current.getFirstName())) {
            text.append("firstName: ").append(current.getFirstName()).append(" -> ").append(firstName).append("; ");
        }
        if (!lastName.equals(current.getLastName())) {
            text.append("lastName: ").append(current.getLastName()).append(" -> ").append(lastName).append("; ");
        }
        if (active != current.isActive()) {
            text.append("active: ").append(current.isActive()).append(" -> ").append(active).append("; ");
        }
        return text.isEmpty() ? null : text.substring(0, text.length() - 2);
    }

    private static ApplicationException notFound() {
        return new ApplicationException("Teacher not found", HttpStatus.NOT_FOUND, "INSTRUCTOR_NOT_FOUND");
    }
}
