package com.examportal.exam.service;

import com.examportal.common.domain.dto.ExamMetaRequest;
import com.examportal.common.domain.dto.ExamResponse;
import com.examportal.common.domain.entity.Exam;
import com.examportal.common.domain.entity.ExamStatus;
import com.examportal.common.exception.ApplicationException;
import com.examportal.exam.exception.ExamException;
import com.examportal.exam.mapper.ExamMapper;
import com.examportal.exam.repository.ExamMetaRepository;
import com.examportal.exam.repository.ExamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B10: PATCH /api/exams/{id}/meta changes title and description only, also when the exam has attempts
 */
class ExamMetaServiceTest {

    private static final long EXAM_ID = 11L;
    private static final UUID OWNER = UUID.randomUUID();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ExamRepository examRepository = mock(ExamRepository.class);
    private final ExamMetaRepository metaRepository = mock(ExamMetaRepository.class);
    private final ExamAccessPolicy accessPolicy = mock(ExamAccessPolicy.class);
    private final GroupAuditService auditService = mock(GroupAuditService.class);
    private final ExamCacheService cacheService = mock(ExamCacheService.class);
    private final ExamMapper mapper = mock(ExamMapper.class);
    private final TransactionalOperator transactionalOperator = mock(TransactionalOperator.class);
    private final com.examportal.exam.history.ExamHistoryService history = mock(com.examportal.exam.history.ExamHistoryService.class);
    private ExamMetaService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(transactionalOperator.transactional(any(Mono.class))).thenAnswer(call -> call.getArgument(0));
        when(history.record(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyList())).thenReturn(Mono.empty());
        // An ACTIVE exam that has attempts: the meta change does not even ask, it touches nothing that decides a result
        when(examRepository.findById(EXAM_ID)).thenReturn(Mono.just(Exam.builder().id(EXAM_ID).title("Old title")
                .status(ExamStatus.ACTIVE).createdBy(OWNER).active(true).build()));
        when(accessPolicy.requireAccess(any(), anyString(), any(Exam.class))).thenAnswer(call -> Mono.just(call.getArgument(2)));
        when(accessPolicy.canManage(any(), anyString(), any(Exam.class))).thenReturn(true);
        when(metaRepository.update(eq(EXAM_ID), anyBoolean(), any(), anyBoolean(), any())).thenReturn(Mono.just(1L));
        when(auditService.record(any(), anyString(), isNull(), any(), anyString())).thenReturn(Mono.empty());
        when(cacheService.invalidateExamCache(EXAM_ID)).thenReturn(Mono.empty());
        when(mapper.toResponse(any(Exam.class))).thenReturn(ExamResponse.builder().id(EXAM_ID).build());
        service = new ExamMetaService(examRepository, metaRepository, accessPolicy, auditService, cacheService, mapper,
                transactionalOperator, history);
    }

    private static ExamMetaRequest body(String json) throws Exception {
        return JSON.readValue(json, ExamMetaRequest.class);
    }

    private static void assertCode(Throwable error, HttpStatus status, String code) {
        assertThat(error).isInstanceOf(ApplicationException.class);
        assertThat(((ApplicationException) error).getStatus()).isEqualTo(status);
        assertThat(((ApplicationException) error).getErrorCode()).isEqualTo(code);
    }

    @Test
    void anExamWithAttemptsGetsANewTitleAndAnAuditRow() throws Exception {
        StepVerifier.create(service.updateMeta(EXAM_ID, body("{\"title\":\"  New title \"}"), OWNER, "INSTRUCTOR"))
                .expectNextCount(1)
                .verifyComplete();

        verify(metaRepository).update(EXAM_ID, true, "New title", false, null);
        verify(auditService).record(eq(OWNER), eq(GroupAuditService.EXAM_META_UPDATED), isNull(), eq(OWNER),
                eq("examId=11 title=Old title -> New title"));
        verify(cacheService).invalidateExamCache(EXAM_ID);
    }

    @Test
    void anEmptyDescriptionRemovesItAndTheTitleStays() throws Exception {
        StepVerifier.create(service.updateMeta(EXAM_ID, body("{\"description\":\"  \"}"), OWNER, "ADMIN"))
                .expectNextCount(1)
                .verifyComplete();
        verify(metaRepository).update(EXAM_ID, false, null, true, null);
    }

    @Test
    void anyOtherFieldIs400AndNothingIsWritten() throws Exception {
        for (String json : new String[]{"{\"title\":\"New title\",\"durationMinutes\":90}",
                "{\"questions\":[]}", "{\"title\":\"New title\",\"endTime\":\"2026-12-01T10:00:00\"}"}) {
            StepVerifier.create(service.updateMeta(EXAM_ID, body(json), OWNER, "ADMIN"))
                    .expectErrorSatisfies(e -> assertCode(e, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR"))
                    .verify();
        }
        verify(metaRepository, never()).update(any(), anyBoolean(), any(), anyBoolean(), any());
    }

    @Test
    void nothingToChangeOrABlankTitleIs400() throws Exception {
        for (String json : new String[]{"{}", "{\"title\":\"   \"}", "{\"title\":null}", "{\"title\":\"ab\"}"}) {
            StepVerifier.create(service.updateMeta(EXAM_ID, body(json), OWNER, "ADMIN"))
                    .expectErrorSatisfies(e -> assertCode(e, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR"))
                    .verify();
        }
        verify(metaRepository, never()).update(any(), anyBoolean(), any(), anyBoolean(), any());
    }

    @Test
    void theExamOfAnotherInstructorIsTheSame404AsOutsideTheAudience() throws Exception {
        when(accessPolicy.canManage(any(), anyString(), any(Exam.class))).thenReturn(false);
        StepVerifier.create(service.updateMeta(EXAM_ID, body("{\"title\":\"New title\"}"), UUID.randomUUID(), "INSTRUCTOR"))
                .expectErrorSatisfies(e -> assertCode(e, HttpStatus.NOT_FOUND, "EXAM_NOT_FOUND"))
                .verify();

        when(accessPolicy.requireAccess(any(), anyString(), any(Exam.class))).thenReturn(Mono.error(ExamException.notFound(EXAM_ID)));
        StepVerifier.create(service.updateMeta(EXAM_ID, body("{\"title\":\"New title\"}"), UUID.randomUUID(), "INSTRUCTOR"))
                .expectErrorSatisfies(e -> assertCode(e, HttpStatus.NOT_FOUND, "EXAM_NOT_FOUND"))
                .verify();
        verify(metaRepository, never()).update(any(), anyBoolean(), any(), anyBoolean(), any());
    }
}
