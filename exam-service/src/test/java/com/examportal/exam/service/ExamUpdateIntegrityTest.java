package com.examportal.exam.service;

import com.examportal.common.domain.dto.ExamResponse;
import com.examportal.common.domain.dto.UpdateExamRequest;
import com.examportal.common.domain.entity.Exam;
import com.examportal.common.domain.entity.ExamAccessType;
import com.examportal.common.domain.entity.ExamStatus;
import com.examportal.common.domain.entity.TimingMode;
import com.examportal.exam.structure.ExamState;
import com.examportal.exam.structure.ExamStateRepository;
import com.examportal.exam.structure.ExamStructureWriter;
import com.examportal.exam.structure.StructurePlan;
import com.examportal.exam.structure.StructurePlanner;
import com.examportal.common.exception.ApplicationException;
import com.examportal.exam.mapper.ExamMapper;
import com.examportal.exam.repository.*;
import com.examportal.exam.validator.ExamValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B9: what decides the results of an exam with attempts (questions, options, points, the answer key,
 * the order, the duration) can no longer be changed; an exam without attempts is edited as before.
 */
class ExamUpdateIntegrityTest {

    private static final long EXAM_ID = 7L;
    private static final UUID OWNER = UUID.randomUUID();

    private final ExamRepository examRepository = mock(ExamRepository.class);
    private final QuestionRepository questionRepository = mock(QuestionRepository.class);
    private final QuestionOptionRepository optionRepository = mock(QuestionOptionRepository.class);
    private final ExamEditLock editLock = mock(ExamEditLock.class);
    private final ExamCacheService cacheService = mock(ExamCacheService.class);
    private final ExamAccessPolicy accessPolicy = mock(ExamAccessPolicy.class);
    private final GroupAuditService auditService = mock(GroupAuditService.class);
    private final ExamMediaService examMedia = mock(ExamMediaService.class);
    private final ExamValidator validator = mock(ExamValidator.class);
    private final ExamMapper mapper = mock(ExamMapper.class);
    private final TransactionalOperator transactionalOperator = mock(TransactionalOperator.class);
    private final ExamStateRepository stateRepository = mock(ExamStateRepository.class);
    private final ExamStructureWriter structureWriter = mock(ExamStructureWriter.class);
    private final ExamBlockRepository blockRepository = mock(ExamBlockRepository.class);

    private ExamService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(transactionalOperator.transactional(any(Mono.class))).thenAnswer(call -> call.getArgument(0));
        when(accessPolicy.requireAccess(any(), anyString(), any(Exam.class))).thenAnswer(call -> Mono.just(call.getArgument(2)));
        when(accessPolicy.canManage(any(), anyString(), any(Exam.class))).thenReturn(true);
        when(editLock.lockForEdit(EXAM_ID)).thenReturn(Mono.just(EXAM_ID));
        when(validator.validateUpdateRequest(any(), any())).thenReturn(Mono.empty());
        when(validator.validateTiming(any(), any(), any(), any(), any())).thenReturn(Mono.just(30));
        when(blockRepository.findByExamIdOrderByPosition(EXAM_ID)).thenReturn(Flux.empty());
        when(examMedia.validate(any(), any(), anyString(), anyLong())).thenReturn(Mono.empty());
        when(examMedia.withMedia(any(ExamResponse.class), anyBoolean())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(examRepository.save(any(Exam.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(examRepository.updateTotalQuestions(anyLong(), any())).thenReturn(Mono.just(1));
        when(questionRepository.findByExamIdOrderByOrderIndex(EXAM_ID)).thenReturn(Flux.empty());
        when(questionRepository.findActiveByExamIdOrderByOrderIndex(EXAM_ID)).thenReturn(Flux.empty());
        when(questionRepository.deleteByExamId(EXAM_ID)).thenReturn(Mono.empty());
        when(mapper.toResponse(any(Exam.class), anyList())).thenReturn(ExamResponse.builder().id(EXAM_ID).build());
        when(cacheService.invalidateExamCache(EXAM_ID)).thenReturn(Mono.empty());
        when(cacheService.cacheExam(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(stateRepository.load(EXAM_ID)).thenReturn(Mono.just(new ExamState(EXAM_ID, OWNER, "Old", null, ExamStatus.DRAFT,
                30, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(2), null, false, false, false, 1, null,
                null, ExamAccessType.PUBLIC, TimingMode.EXAM_TIMER, false, true, true,
                List.of(new ExamState.BlockState(70L, 1, "Əsas blok", null, null, 0, true, false, true, List.of())))));
        when(structureWriter.apply(eq(EXAM_ID), any(ExamState.class), any(StructurePlan.class))).thenReturn(Mono.empty());
        service = new ExamService(examRepository, questionRepository, optionRepository, mock(ExamSessionRepository.class),
                mock(ExamAttemptRepository.class), editLock, mock(ExamManageRepository.class),
                mock(GroupMembershipRepository.class), mock(ExamDayClock.class), cacheService,
                mock(AnswerSubmissionService.class), accessPolicy, mock(UserGroupRepository.class),
                mock(ExamAudienceRepository.class), auditService, examMedia, validator, mapper, transactionalOperator,
                stateRepository, new StructurePlanner(), structureWriter, blockRepository, new ExamFeatures(false, false));
    }

    private void examIs(ExamStatus status) {
        when(examRepository.findById(EXAM_ID)).thenReturn(Mono.just(Exam.builder().id(EXAM_ID).status(status)
                .createdBy(OWNER).active(true).startTime(LocalDateTime.now().plusDays(1))
                .endTime(LocalDateTime.now().plusDays(2)).build()));
    }

    private static UpdateExamRequest request() {
        UpdateExamRequest request = new UpdateExamRequest();
        request.setTitle("Changed");
        request.setQuestions(List.of());
        return request;
    }

    private static void assertRefused(Throwable error, HttpStatus status, String code) {
        assertThat(error).isInstanceOf(ApplicationException.class);
        ApplicationException app = (ApplicationException) error;
        assertThat(app.getStatus()).isEqualTo(status);
        assertThat(app.getErrorCode()).isEqualTo(code);
    }

    @Test
    void anExamWithAttemptsIsRefusedWith409AndNothingIsReplaced() {
        // Any status: a SCHEDULED exam can have attempts after a reschedule, a DRAFT never should but is covered too
        for (ExamStatus status : List.of(ExamStatus.DRAFT, ExamStatus.SCHEDULED, ExamStatus.ACTIVE, ExamStatus.COMPLETED,
                ExamStatus.ARCHIVED)) {
            examIs(status);
            when(editLock.hasSessions(EXAM_ID)).thenReturn(Mono.just(true));

            StepVerifier.create(service.updateExam(EXAM_ID, request(), OWNER, "INSTRUCTOR"))
                    .expectErrorSatisfies(error -> assertRefused(error, HttpStatus.CONFLICT, "EXAM_HAS_SESSIONS"))
                    .verify();
        }
        verify(questionRepository, never()).deleteByExamId(anyLong());
        verify(optionRepository, never()).deleteByQuestionId(anyLong());
        verify(structureWriter, never()).apply(anyLong(), any(), any());
        verify(examRepository, never()).save(any(Exam.class));
        verify(examRepository, never()).updateTotalQuestions(anyLong(), any());
    }

    @Test
    void theLockIsTakenBeforeTheSessionsAreCounted() {
        examIs(ExamStatus.SCHEDULED);
        when(editLock.hasSessions(EXAM_ID)).thenReturn(Mono.just(true));

        StepVerifier.create(service.updateExam(EXAM_ID, request(), OWNER, "ADMIN")).expectError().verify();

        InOrder order = inOrder(editLock);
        order.verify(editLock).lockForEdit(EXAM_ID);
        order.verify(editLock).hasSessions(EXAM_ID);
    }

    @Test
    void anExamWithoutAttemptsIsStillEditedAndItsQuestionsChangedInPlace() {
        for (ExamStatus status : List.of(ExamStatus.DRAFT, ExamStatus.SCHEDULED)) {
            examIs(status);
            when(editLock.hasSessions(EXAM_ID)).thenReturn(Mono.just(false));

            StepVerifier.create(service.updateExam(EXAM_ID, request(), OWNER, "INSTRUCTOR"))
                    .expectNextMatches(response -> Long.valueOf(EXAM_ID).equals(response.getId()))
                    .verifyComplete();
        }
        // B12a: written in place, never deleted and created again
        verify(structureWriter, org.mockito.Mockito.times(2)).apply(eq(EXAM_ID), any(ExamState.class), any(StructurePlan.class));
        verify(questionRepository, never()).deleteByExamId(anyLong());
        verify(examRepository, org.mockito.Mockito.times(2)).save(any(Exam.class));
    }

    @Test
    void anActiveExamWithoutAttemptsKeepsTheOldAnswer() {
        examIs(ExamStatus.ACTIVE);
        when(editLock.hasSessions(EXAM_ID)).thenReturn(Mono.just(false));

        StepVerifier.create(service.updateExam(EXAM_ID, request(), OWNER, "INSTRUCTOR"))
                .expectErrorSatisfies(error -> assertRefused(error, HttpStatus.BAD_REQUEST, "EXAM_CANNOT_MODIFY"))
                .verify();
        verify(questionRepository, never()).deleteByExamId(anyLong());
    }

    @Test
    void anExamThatIsGoneIsA404() {
        examIs(ExamStatus.DRAFT);
        when(editLock.lockForEdit(EXAM_ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.updateExam(EXAM_ID, request(), OWNER, "ADMIN"))
                .expectErrorSatisfies(error -> assertRefused(error, HttpStatus.NOT_FOUND, "EXAM_NOT_FOUND"))
                .verify();
        verify(editLock, never()).hasSessions(anyLong());
        verify(auditService, never()).record(any(), anyString(), isNull(), any(), anyString());
        verify(examRepository, never()).reschedule(eq(EXAM_ID), any(), any(), anyString(), any(), anyBoolean(), any());
    }
}
