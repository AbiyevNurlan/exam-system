package com.examportal.exam.service;

import com.examportal.common.domain.entity.Exam;
import com.examportal.common.domain.entity.ExamAccessType;
import com.examportal.common.domain.entity.ExamSession;
import com.examportal.exam.exception.ExamException;
import com.examportal.exam.repository.ExamRepository;
import com.examportal.exam.repository.ExamSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Exam Access Policy
 * The one place that decides who may see and take an exam. Every endpoint asks here; nothing else
 * compares access types, audience groups or session owners.
 *
 * Rule: ADMIN and the INSTRUCTOR who owns the exam always; an INSTRUCTOR nothing else (B13: teachers are
 * isolated, so no PUBLIC exam and no audience of another teacher, OwnershipPolicy); for everybody else a
 * PUBLIC exam every authenticated user and a GROUP exam only members of at least one of its active audience
 * groups. An exam the caller may not access or manage is answered as 404, exactly like one that does not exist.
 * B13b: a PAID_OPEN exam is open to nobody here but ADMIN and its owner (guests who paid take it through
 * com.examportal.paid); every exam that is not PAID_OPEN follows the rule above as before.
 *
 * Losing access (removed from the group, audience changed) blocks new starts only: a session belongs
 * to the user who opened it and can be continued, submitted and read without a new access check.
 */
@Component
@RequiredArgsConstructor
public class ExamAccessPolicy {

    private final ExamRepository examRepository;
    private final ExamSessionRepository sessionRepository;

    /**
     * Whether the user may see and start this exam
     */
    public Mono<Boolean> canAccess(UUID userId, String role, Exam exam) {
        if (canManage(userId, role, exam)) {
            return Mono.just(true);
        }
        if (OwnershipPolicy.isInstructor(role)) {
            return Mono.just(false);
        }
        // B13b: a paid open exam is taken only by guests who paid (com.examportal.paid), never through the audience
        if (exam.isPaidOpen()) {
            return Mono.just(false);
        }
        if (exam.getAccessType() == ExamAccessType.PUBLIC) {
            return Mono.just(true);
        }
        // GROUP, and anything unknown, is closed unless a membership says otherwise
        return examRepository.isAudienceMember(exam.getId(), userId);
    }

    /**
     * The exam itself when the user may access it, otherwise the same 404 as for an unknown exam
     */
    public Mono<Exam> requireAccess(UUID userId, String role, Exam exam) {
        return canAccess(userId, role, exam)
                .flatMap(allowed -> allowed
                        ? Mono.just(exam)
                        : Mono.error(ExamException.notFound(exam.getId())));
    }

    /**
     * ADMIN, or the INSTRUCTOR who owns the exam: sees the answer key and the results
     */
    public boolean canManage(UUID userId, String role, Exam exam) {
        return OwnershipPolicy.owns(userId, role, exam.getCreatedBy());
    }

    /**
     * The exam when the caller may manage it (results, grading, review, monitoring, analytics, readiness):
     * ADMIN every exam, an INSTRUCTOR the own ones, the ownership in the SQL condition. An exam that does not
     * exist, was deleted or belongs to someone else is the same 404 EXAM_NOT_FOUND (B13: before, another
     * owner's PUBLIC exam was a 403 EXAM_NOT_OWNER, which told that it exists).
     */
    public Mono<Exam> findManageableExam(Long examId, UUID userId, String role) {
        return examRepository.findManageableById(examId, OwnershipPolicy.isAdmin(role), owner(userId, role))
                .switchIfEmpty(Mono.error(ExamException.notFound(examId)));
    }

    /**
     * The session when the caller may manage its exam, the ownership in the SQL condition; a session of
     * another owner's exam is the same 404 SESSION_NOT_FOUND as one that does not exist
     */
    public Mono<ExamSession> findManageableSession(String sessionId, UUID userId, String role) {
        return sessionRepository.findManageableBySessionId(sessionId, OwnershipPolicy.isAdmin(role), owner(userId, role))
                .switchIfEmpty(Mono.error(ExamException.sessionNotFound(sessionId)));
    }

    /**
     * The 404 every change of an exam answers when the caller may see it but not manage it: the same as for
     * an exam that does not exist (B13, replaces 403 EXAM_NOT_OWNER)
     */
    public static ExamException notManageable(Long examId) {
        return ExamException.notFound(examId);
    }

    // The owner bound to the SQL condition; never null (ADMIN's condition does not look at it)
    private static UUID owner(UUID userId, String role) {
        UUID owner = OwnershipPolicy.ownerFilter(userId, role);
        return owner == null ? OwnershipPolicy.NOBODY : owner;
    }

    /**
     * The session itself when it belongs to the user, otherwise the same 404 as for an unknown session.
     * Deliberately not tied to the exam's current audience (see the class comment).
     */
    public Mono<ExamSession> requireOwnSession(UUID userId, ExamSession session) {
        return session.getUserId().equals(userId)
                ? Mono.just(session)
                : Mono.error(ExamException.sessionNotFound(session.getSessionId()));
    }

    /**
     * One page of the SCHEDULED or ACTIVE exams the user may access and can still start or resume.
     * The filter runs in SQL (ExamRepository.ACCESS_CONDITION), so paging and totals are exact.
     */
    public Flux<Exam> findAvailableExams(UUID userId, String role, int limit, long offset) {
        return examRepository.findAvailableExams(userId, OwnershipPolicy.isAdmin(role), OwnershipPolicy.isInstructor(role),
                LocalDateTime.now(), limit, offset);
    }

    public Mono<Long> countAvailableExams(UUID userId, String role) {
        return examRepository.countAvailableExams(userId, OwnershipPolicy.isAdmin(role), OwnershipPolicy.isInstructor(role),
                LocalDateTime.now());
    }
}