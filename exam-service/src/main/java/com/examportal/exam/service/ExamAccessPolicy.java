package com.examportal.exam.service;

import com.examportal.common.domain.entity.Exam;
import com.examportal.common.domain.entity.ExamAccessType;
import com.examportal.common.domain.entity.ExamSession;
import com.examportal.exam.exception.ExamException;
import com.examportal.exam.repository.ExamRepository;
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
 * Rule: ADMIN and the INSTRUCTOR who owns the exam always; a PUBLIC exam every authenticated user;
 * a GROUP exam only members of at least one of its active audience groups.
 * An exam the caller may not access is answered as 404, exactly like one that does not exist.
 *
 * Losing access (removed from the group, audience changed) blocks new starts only: a session belongs
 * to the user who opened it and can be continued, submitted and read without a new access check.
 */
@Component
@RequiredArgsConstructor
public class ExamAccessPolicy {

    private static final String ADMIN = "ADMIN";
    private static final String INSTRUCTOR = "INSTRUCTOR";

    private final ExamRepository examRepository;

    /**
     * Whether the user may see and start this exam
     */
    public Mono<Boolean> canAccess(UUID userId, String role, Exam exam) {
        if (canManage(userId, role, exam) || exam.getAccessType() == ExamAccessType.PUBLIC) {
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
        return ADMIN.equals(role) || (INSTRUCTOR.equals(role) && userId.equals(exam.getCreatedBy()));
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
        return examRepository.findAvailableExams(userId, ADMIN.equals(role), INSTRUCTOR.equals(role),
                LocalDateTime.now(), limit, offset);
    }

    public Mono<Long> countAvailableExams(UUID userId, String role) {
        return examRepository.countAvailableExams(userId, ADMIN.equals(role), INSTRUCTOR.equals(role),
                LocalDateTime.now());
    }
}