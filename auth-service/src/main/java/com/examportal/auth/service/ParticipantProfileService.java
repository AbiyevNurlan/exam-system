package com.examportal.auth.service;

import com.examportal.auth.config.ParticipantProfileProperties;
import com.examportal.auth.repository.ParticipantProfileRepository;
import com.examportal.auth.repository.UserRepository;
import com.examportal.common.domain.dto.profile.ParticipantProfileDtos;
import com.examportal.common.domain.profile.ParticipantProfileRules;
import com.examportal.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Participant Profile Service (B11)
 * Reads and writes the profile of an account. A change runs in one transaction: the user row is locked
 * (FOR UPDATE: the account exists and is active, and two changes of one profile wait for each other),
 * the old profile is read, the new one checked and normalized (ParticipantProfileRules), stored by an
 * upsert, and an audit row with "field: before -> after" is written. A request that changes nothing
 * writes nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParticipantProfileService {

    private final ParticipantProfileRepository profileRepository;
    private final UserRepository userRepository;
    private final AccountAuditLog auditLog;
    private final ParticipantProfileProperties properties;
    // @Transactional is not applied at runtime in this service, so the TransactionalOperator is the real boundary
    private final TransactionalOperator transactionalOperator;

    /**
     * The profile of an active account; empty Optional when it has none. Unknown or inactive account: 404.
     */
    public Mono<Optional<ParticipantProfileDtos.Profile>> get(UUID userId) {
        return userRepository.findById(userId)
                .filter(user -> user.isActive())
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("User", String.valueOf(userId))))
                .flatMap(user -> profileRepository.findByUserId(userId).map(Optional::of).defaultIfEmpty(Optional.empty()));
    }

    /**
     * The own profile with what can be chosen: the active categories and the required fields per category
     */
    public Mono<ParticipantProfileDtos.MyProfile> mine(UUID userId) {
        return Mono.zip(get(userId), profileRepository.activeCategories())
                .map(found -> ParticipantProfileDtos.MyProfile.builder()
                        .profile(found.getT1().orElse(null))
                        .categories(found.getT2())
                        .requiredFields(requiredFields())
                        .build());
    }

    /**
     * Replaces the profile of the user. actorId = userId: the participant's own change; otherwise an
     * administrator's (the caller has checked the role). Returns the stored profile.
     */
    public Mono<ParticipantProfileDtos.Profile> update(UUID userId, ParticipantProfileDtos.UpdateRequest request, UUID actorId) {
        boolean own = userId.equals(actorId);
        return profileRepository.activeCategories()
                // Validation errors come before any lock or read of the account
                .map(categories -> ParticipantProfileRules.normalize(request, categories, requiredFields(),
                        LocalDate.now(ZoneOffset.UTC)))
                .flatMap(values -> userRepository.findByIdForUpdate(userId)
                        .filter(user -> user.isActive())
                        .switchIfEmpty(Mono.error(new ResourceNotFoundException("User", String.valueOf(userId))))
                        .flatMap(user -> profileRepository.findByUserId(userId).map(Optional::of).defaultIfEmpty(Optional.empty()))
                        .flatMap(before -> {
                            String changes = ParticipantProfileRules.describeChanges(before.orElse(null), values);
                            if (changes.isEmpty()) {
                                return Mono.just(before.get());
                            }
                            String action = own ? AccountAuditLog.PROFILE_DETAILS_UPDATED : AccountAuditLog.PARTICIPANT_PROFILE_UPDATED;
                            return profileRepository.upsert(userId, values, actorId)
                                    .then(auditLog.record(actorId, action, userId, changes))
                                    .then(profileRepository.findByUserId(userId));
                        }))
                .as(transactionalOperator::transactional)
                .doOnSuccess(profile -> log.info("Participant profile of {} saved by {}", userId, own ? "the participant" : actorId));
    }

    private Map<String, List<String>> requiredFields() {
        return properties.getRequiredFields() == null ? Map.of() : properties.getRequiredFields();
    }
}
