package com.examportal.exam.service;

import com.examportal.common.domain.dto.FraudEventRequest;
import com.examportal.exam.exception.ExamException;
import com.examportal.exam.repository.ExamSessionRepository;
import com.examportal.exam.repository.SessionFraudEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Fraud Event Service
 * Takes the events a participant's browser reports during a session (tab switch, leaving full
 * screen, copy attempt, ...) and stores them. That is all: no score, no penalty and no session is
 * ever changed by an event; a person looks at them later.
 *
 * The reports come from the participant's own browser, so they are not trusted and are bounded:
 * only the owner's running session, at most exam.fraud.max-events-per-session rows per session
 * (further events are accepted and dropped), details limited by the request DTO.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FraudEventService {

    // A browser clock that runs ahead is not allowed to date an event into the future
    private static final int MAX_CLOCK_SKEW_MINUTES = 5;

    private final ExamSessionRepository sessionRepository;
    private final SessionFraudEventRepository eventRepository;
    private final ExamAccessPolicy accessPolicy;
    private final TransactionalOperator transactionalOperator;

    @Value("${exam.fraud.max-events-per-session:300}")
    private int maxEventsPerSession;

    /**
     * Record one event of the caller's own running session. Completes also when the event was
     * dropped because the session already has its maximum number of events.
     */
    public Mono<Void> record(String sessionId, FraudEventRequest request, UUID userId) {
        return sessionRepository.findBySessionId(sessionId)
                .switchIfEmpty(Mono.error(ExamException.sessionNotFound(sessionId)))
                // Someone else's session answers 404, same as an unknown id
                .flatMap(session -> accessPolicy.requireOwnSession(userId, session))
                .flatMap(session -> {
                    if (!session.isActive()) {
                        return Mono.error(ExamException.sessionAlreadyCompleted());
                    }
                    LocalDateTime now = LocalDateTime.now();
                    LocalDateTime occurredAt = request.getOccurredAt();
                    if (occurredAt == null || occurredAt.isAfter(now.plusMinutes(MAX_CLOCK_SKEW_MINUTES))) {
                        occurredAt = now;
                    }
                    String details = request.getDetails() == null || request.getDetails().isBlank()
                            ? null
                            : request.getDetails().trim();

                    // @Transactional is not applied at runtime in this service: the TransactionalOperator is the boundary
                    return transactionalOperator.transactional(eventRepository.insertWhileBelowLimit(
                                    session.getId(), request.getType().name(), occurredAt, details, maxEventsPerSession))
                            .doOnNext(written -> {
                                if (written == 0) {
                                    // The limit, or the session ended in between: either way nothing to tell the client
                                    log.debug("Fraud event of session {} not stored", sessionId);
                                }
                            });
                })
                .then();
    }
}