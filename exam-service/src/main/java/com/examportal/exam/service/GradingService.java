package com.examportal.exam.service;

import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.grading.GradeRequest;
import com.examportal.common.domain.dto.grading.GradingAnswerResponse;
import com.examportal.common.domain.dto.grading.GradingQueueRowResponse;
import com.examportal.common.domain.dto.grading.SessionGradingResponse;
import com.examportal.common.domain.dto.internal.UserLookupResponse.UserRef;
import com.examportal.common.domain.entity.ExamSession;
import com.examportal.common.domain.entity.GradingStatus;
import com.examportal.common.domain.entity.QuestionType;
import com.examportal.common.domain.entity.SessionStatus;
import com.examportal.common.exception.ValidationException;
import com.examportal.exam.exception.ExamException;
import com.examportal.exam.repository.ExamSessionRepository;
import com.examportal.exam.repository.GradingRepository;
import com.examportal.exam.repository.GradingRepository.GradeTarget;
import com.examportal.exam.repository.GradingRepository.OpenAnswer;
import com.examportal.exam.repository.GradingRepository.QueueFilter;
import com.examportal.exam.repository.GradingRepository.QueueRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Grading Service
 * Manual grading of open-ended answers by ADMIN or the INSTRUCTOR who owns the exam.
 *
 * An open-ended answer with text gets no points at submit and waits for a person; the session is
 * PENDING until every such answer is graded. A grade can be changed later (regrading). Every
 * grading is all-or-nothing per request, protected against a second grader by the answer's
 * gradeVersion, and leaves one audit row per answer with the old and the new points.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GradingService {

    private static final int MAX_PAGE_SIZE = 50;

    private final ExamSessionRepository sessionRepository;
    private final GradingRepository gradingRepository;
    private final ExamAccessPolicy accessPolicy;
    private final ParticipantDirectory participants;
    private final GroupAuditService auditService;
    private final TransactionalOperator transactionalOperator;

    /**
     * One page of the grading queue of an exam: completed sessions that need (PENDING, the default)
     * or needed (COMPLETE) a grader, or both (ALL), oldest submission first.
     */
    public Mono<PageResponse<GradingQueueRowResponse>> getQueue(Long examId, String status, String q,
                                                                 int page, int size, UUID userId, String role) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        GradingStatus statusFilter = queueStatus(status);
        String term = participants.searchTerm(q);

        return accessPolicy.findManageableExam(examId, userId, role)
                // With a search text the matching users are asked for first, and name the rows too
                .flatMap(exam -> term == null
                        ? Mono.just(new Search(null, Map.of()))
                        : participants.search(term).map(found -> new Search(found.keySet().toArray(new UUID[0]), found)))
                .flatMap(search -> {
                    if (search.userIds() != null && search.userIds().length == 0) {
                        return Mono.just(PageResponse.<GradingQueueRowResponse>of(List.of(), pageNumber, pageSize, 0));
                    }
                    QueueFilter filter = new QueueFilter(examId, statusFilter, search.userIds());
                    return gradingRepository.countQueue(filter)
                            .flatMap(total -> gradingRepository.findQueuePage(filter, pageSize, (long) pageNumber * pageSize)
                                    .collectList()
                                    .flatMap(rows -> (search.userIds() != null
                                            ? Mono.just(search.users())
                                            // One lookup for the whole page
                                            : participants.names(rows.stream().map(QueueRow::userId).toList()))
                                            .map(users -> PageResponse.of(
                                                    rows.stream().map(row -> toQueueRow(row, users.get(row.userId()))).toList(),
                                                    pageNumber, pageSize, total))));
                });
    }

    /**
     * The open-ended answers of one session with the session's current score
     */
    public Mono<SessionGradingResponse> getSessionGrading(String sessionId, UUID userId, String role) {
        return findManageableSession(sessionId, userId, role)
                .flatMap(this::toSessionGrading);
    }

    /**
     * Give points to open-ended answers of one completed session: all of them or none.
     * 400 GRADE_INVALID for anything wrong with the request, 409 SESSION_NOT_COMPLETED while the
     * session runs, 409 GRADE_CONFLICT when an answer was graded by someone else since the grader
     * looked at it (nothing is written then).
     */
    public Mono<SessionGradingResponse> grade(String sessionId, List<GradeRequest> grades, UUID userId, String role) {
        return Mono.defer(() -> {
                    validateShape(grades);
                    return findManageableSession(sessionId, userId, role);
                })
                // @Transactional is not applied at runtime in this service: the TransactionalOperator is the boundary
                .flatMap(session -> transactionalOperator.transactional(applyGrades(session, grades, userId))
                        .then(Mono.defer(() -> sessionRepository.findBySessionId(sessionId))))
                .flatMap(this::toSessionGrading)
                .doOnSuccess(result -> log.info("Session {} graded by {}: {} answers, now {}", sessionId, userId,
                        grades.size(), result.getGradingStatus()));
    }

    // ==================== Private Helper Methods ====================

    private record Search(UUID[] userIds, Map<UUID, UserRef> users) {
    }

    /**
     * Everything inside one transaction. The session row is locked first, so a second grading of
     * the same session waits here and then meets the versions the first one stored.
     */
    private Mono<Void> applyGrades(ExamSession session, List<GradeRequest> grades, UUID graderId) {
        Long sessionPk = session.getId();
        Long[] answerIds = grades.stream().map(GradeRequest::getAnswerId).toArray(Long[]::new);
        LocalDateTime now = LocalDateTime.now();

        return gradingRepository.lockSession(sessionPk)
                .flatMap(status -> SessionStatus.COMPLETED.name().equals(status)
                        ? Mono.just(status)
                        : Mono.<String>error(ExamException.sessionNotCompleted()))
                .flatMap(status -> gradingRepository.findGradeTargets(sessionPk, answerIds)
                        .collectMap(GradeTarget::answerId))
                .flatMap(targets -> {
                    // Every grade is checked before the first one is written
                    for (GradeRequest grade : grades) {
                        GradeTarget target = targets.get(grade.getAnswerId());
                        if (target == null) {
                            return Mono.error(ExamException.gradeInvalid("answer " + grade.getAnswerId()
                                    + " does not belong to this session"));
                        }
                        if (target.questionType() != QuestionType.OPEN_ENDED) {
                            return Mono.error(ExamException.gradeInvalid("answer " + grade.getAnswerId()
                                    + " is not an answer to an open-ended question"));
                        }
                        if (grade.getPoints().compareTo(target.maxPoints()) > 0) {
                            return Mono.error(ExamException.gradeInvalid("answer " + grade.getAnswerId()
                                    + " cannot get more than " + target.maxPoints().toPlainString() + " points"));
                        }
                        if (target.gradeVersion() != grade.getExpectedVersion()) {
                            return Mono.error(ExamException.gradeConflict(grade.getAnswerId(), target.gradeVersion()));
                        }
                    }
                    return Flux.fromIterable(grades)
                            // Sequential: these writes share the one connection of the transaction
                            .concatMap(grade -> {
                                GradeTarget target = targets.get(grade.getAnswerId());
                                String feedback = grade.getFeedback() == null || grade.getFeedback().isBlank()
                                        ? null
                                        : grade.getFeedback().trim();
                                return gradingRepository.applyGrade(sessionPk, target.answerId(), grade.getPoints(),
                                                grade.getPoints().compareTo(target.maxPoints()) == 0, feedback,
                                                graderId, now, grade.getExpectedVersion())
                                        // The guarded UPDATE is the last word on the version
                                        .flatMap(written -> written == 1
                                                ? Mono.just(written)
                                                : Mono.<Long>error(ExamException.gradeConflict(target.answerId(), target.gradeVersion())))
                                        .then(auditService.record(graderId,
                                                target.gradeVersion() == 0 ? GroupAuditService.ANSWER_GRADED : GroupAuditService.ANSWER_REGRADED,
                                                null, session.getUserId(),
                                                "examId=" + session.getExamId()
                                                        + " sessionId=" + session.getSessionId()
                                                        + " answerId=" + target.answerId()
                                                        + " questionId=" + target.questionId()
                                                        + " points=" + plain(target.pointsEarned()) + " -> " + plain(grade.getPoints())
                                                        + " max=" + plain(target.maxPoints())
                                                        + " version=" + target.gradeVersion() + " -> " + (target.gradeVersion() + 1)));
                            })
                            .then(gradingRepository.recalculateSession(sessionPk))
                            .then();
                });
    }

    /**
     * What can be checked without the database; anything wrong is a 400 before a transaction starts
     */
    private void validateShape(List<GradeRequest> grades) {
        if (grades == null || grades.isEmpty()) {
            throw new ValidationException("At least one grade is required");
        }
        if (grades.size() > GradeRequest.MAX_ITEMS) {
            throw new ValidationException("At most " + GradeRequest.MAX_ITEMS + " grades per request");
        }
        Set<Long> seen = new HashSet<>();
        for (GradeRequest grade : grades) {
            if (grade == null || grade.getAnswerId() == null || grade.getPoints() == null || grade.getExpectedVersion() == null) {
                throw ExamException.gradeInvalid("answerId, points and expectedVersion are required");
            }
            if (!seen.add(grade.getAnswerId())) {
                throw ExamException.gradeInvalid("answer " + grade.getAnswerId() + " is listed twice");
            }
            BigDecimal points = grade.getPoints();
            if (points.signum() < 0) {
                throw ExamException.gradeInvalid("points cannot be negative");
            }
            if (points.stripTrailingZeros().scale() > 2) {
                throw ExamException.gradeInvalid("points can have at most 2 decimals");
            }
            if (grade.getFeedback() != null && grade.getFeedback().trim().length() > GradeRequest.MAX_FEEDBACK_LENGTH) {
                throw ExamException.gradeInvalid("feedback cannot exceed " + GradeRequest.MAX_FEEDBACK_LENGTH + " characters");
            }
        }
    }

    /**
     * The session when the caller may manage its exam; an unknown session is a 404
     */
    private Mono<ExamSession> findManageableSession(String sessionId, UUID userId, String role) {
        // A session of another owner's exam is the same 404 SESSION_NOT_FOUND as a missing one (B13)
        return accessPolicy.findManageableSession(sessionId, userId, role);
    }

    private Mono<SessionGradingResponse> toSessionGrading(ExamSession session) {
        return gradingRepository.findOpenAnswers(session.getId())
                .collectList()
                // The participant and the graders are named with one lookup
                .flatMap(answers -> participants.names(Stream.concat(
                                        Stream.of(session.getUserId()),
                                        answers.stream().map(OpenAnswer::gradedBy).filter(Objects::nonNull))
                                .collect(Collectors.toSet()))
                        .map(users -> {
                            UserRef participant = users.get(session.getUserId());
                            return SessionGradingResponse.builder()
                                    .sessionId(session.getSessionId())
                                    .examId(session.getExamId())
                                    .userId(session.getUserId())
                                    .participantName(ParticipantDirectory.fullName(participant))
                                    .email(participant != null ? participant.getEmail() : null)
                                    .participantCode(participant != null ? participant.getParticipantCode() : null)
                                    .status(session.getStatus())
                                    .gradingStatus(session.getGradingStatus())
                                    .submittedAt(session.getSubmittedAt())
                                    .score(session.getScore())
                                    .maxScore(session.getTotalPoints())
                                    .percentage(session.getPercentage())
                                    .passed(session.getPassed())
                                    .answers(answers.stream()
                                            .map(answer -> GradingAnswerResponse.builder()
                                                    .answerId(answer.answerId())
                                                    .questionId(answer.questionId())
                                                    .questionText(answer.questionText())
                                                    .maxPoints(answer.maxPoints())
                                                    .textAnswer(answer.textAnswer())
                                                    .pointsEarned(answer.pointsEarned())
                                                    .feedback(answer.feedback())
                                                    .gradedBy(ParticipantDirectory.fullName(users.get(answer.gradedBy())))
                                                    .gradedById(answer.gradedBy())
                                                    .gradedAt(answer.gradedAt())
                                                    .gradeVersion(answer.gradeVersion())
                                                    .needsManualReview(answer.needsManualReview())
                                                    .build())
                                            .toList())
                                    .build();
                        }));
    }

    private GradingQueueRowResponse toQueueRow(QueueRow row, UserRef user) {
        return GradingQueueRowResponse.builder()
                .sessionId(row.sessionId())
                .userId(row.userId())
                .participantName(ParticipantDirectory.fullName(user))
                .email(user != null ? user.getEmail() : null)
                .participantCode(user != null ? user.getParticipantCode() : null)
                .attemptNumber(row.attemptNumber())
                .submittedAt(row.submittedAt())
                .openAnswerCount(row.openCount())
                .pendingCount(row.pendingCount())
                .gradedCount(row.gradedCount())
                .gradingStatus(row.gradingStatus())
                .build();
    }

    /**
     * PENDING when omitted; ALL (null here) = PENDING and COMPLETE
     */
    private GradingStatus queueStatus(String status) {
        String value = status == null || status.isBlank() ? "PENDING" : status.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (value) {
            case "PENDING" -> GradingStatus.PENDING;
            case "COMPLETE" -> GradingStatus.COMPLETE;
            case "ALL" -> null;
            default -> throw new ValidationException("status must be PENDING, COMPLETE or ALL");
        };
    }

    private static String plain(BigDecimal value) {
        return value == null ? "null" : value.toPlainString();
    }
}
