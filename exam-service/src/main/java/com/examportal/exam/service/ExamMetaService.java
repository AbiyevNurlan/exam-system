package com.examportal.exam.service;

import com.examportal.common.domain.dto.ExamMetaRequest;
import com.examportal.common.domain.dto.ExamResponse;
import com.examportal.common.domain.entity.Exam;
import com.examportal.common.exception.ValidationException;
import com.examportal.exam.exception.ExamException;
import com.examportal.exam.history.ChangeContext;
import com.examportal.exam.history.ExamDiff;
import com.examportal.exam.history.ExamHistoryService;
import com.examportal.exam.mapper.ExamMapper;
import com.examportal.exam.repository.ExamMetaRepository;
import com.examportal.exam.repository.ExamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * PATCH /api/exams/{id}/meta: title and description only, in every status and also when the exam has
 * attempts (they decide no result). Who may: the ADMIN and the INSTRUCTOR who created the exam, by the
 * rules of every other change (outside the audience 404, someone else's exam the same 404, B13).
 * The change and one audit row commit together; the cached exam is dropped afterwards.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamMetaService {

    private static final int MAX_AUDIT_TITLE = 100;

    private final ExamRepository examRepository;
    private final ExamMetaRepository metaRepository;
    private final ExamAccessPolicy accessPolicy;
    private final GroupAuditService auditService;
    private final ExamCacheService cacheService;
    private final ExamMapper mapper;
    private final TransactionalOperator transactionalOperator;
    private final ExamHistoryService history;

    public Mono<ExamResponse> updateMeta(Long examId, ExamMetaRequest request, UUID userId, String role) {
        return updateMeta(examId, request, ChangeContext.of(userId, role));
    }

    /**
     * The same, with the history row META_CHANGED (B12a) in the same transaction
     */
    public Mono<ExamResponse> updateMeta(Long examId, ExamMetaRequest request, ChangeContext change) {
        UUID userId = change.actorId();
        String role = change.role();
        if (!request.getOtherFields().isEmpty()) {
            return Mono.error(new ValidationException("Only title and description can be changed here; not allowed: "
                    + String.join(", ", request.getOtherFields())));
        }
        if (!request.isTitlePresent() && !request.isDescriptionPresent()) {
            return Mono.error(new ValidationException("Nothing to change: send title and/or description"));
        }
        String title = request.isTitlePresent() && request.getTitle() != null ? request.getTitle().trim() : null;
        if (request.isTitlePresent() && (title == null || title.length() < 3 || title.length() > 255)) {
            return Mono.error(new ValidationException("title", "Title must be between 3 and 255 characters"));
        }
        String description = request.isDescriptionPresent() && request.getDescription() != null
                && !request.getDescription().isBlank() ? request.getDescription().trim() : null;

        Mono<Exam> metaChange = examRepository.findById(examId)
                .filter(exam -> !Boolean.FALSE.equals(exam.getActive()))
                .switchIfEmpty(Mono.error(ExamException.notFound(examId)))
                // An exam outside the user's audience is a 404 like an unknown one
                .flatMap(exam -> accessPolicy.requireAccess(userId, role, exam))
                .flatMap(exam -> accessPolicy.canManage(userId, role, exam)
                        ? Mono.just(exam)
                        : Mono.<Exam>error(ExamAccessPolicy.notManageable(examId)))
                .flatMap(exam -> metaRepository.update(examId, request.isTitlePresent(), title,
                                request.isDescriptionPresent(), description)
                        .flatMap(rows -> rows == 1 ? Mono.just(exam) : Mono.<Exam>error(ExamException.notFound(examId))))
                .flatMap(exam -> auditService.record(userId, GroupAuditService.EXAM_META_UPDATED, null, exam.getCreatedBy(),
                                "examId=" + examId
                                        + (request.isTitlePresent() ? " title=" + cut(exam.getTitle()) + " -> " + cut(title) : "")
                                        + (request.isDescriptionPresent() ? " description=changed" : ""))
                        .then(history.record(examId, exam.getCreatedBy(), exam.getStatus(), change, ExamDiff.meta(examId,
                                exam.getTitle(), request.isTitlePresent() ? title : exam.getTitle(),
                                exam.getDescription(), request.isDescriptionPresent() ? description : exam.getDescription())))
                        .then(Mono.defer(() -> examRepository.findById(examId))));

        return transactionalOperator.transactional(metaChange)
                .flatMap(exam -> cacheService.invalidateExamCache(examId).thenReturn(mapper.toResponse(exam)))
                .doOnSuccess(exam -> log.info("Exam {} title/description changed by {}", examId, userId));
    }

    private static String cut(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > MAX_AUDIT_TITLE ? text.substring(0, MAX_AUDIT_TITLE) : text;
    }
}
