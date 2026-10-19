package com.examportal.exam.service;

import com.examportal.common.domain.dto.ExamResponse;
import com.examportal.common.domain.dto.RescheduleExamRequest;
import com.examportal.common.domain.entity.Exam;
import com.examportal.common.domain.entity.ExamStatus;
import com.examportal.common.exception.ApplicationException;
import com.examportal.exam.mapper.ExamMapper;
import com.examportal.exam.repository.*;
import com.examportal.exam.validator.ExamValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B9: a finished or archived exam with attempts cannot be rescheduled to a later start (SCHEDULED
 * would open it for editing); reopening it at once, and everything without attempts, works as before.
 */
class ExamRescheduleIntegrityTest {

    private static final long EXAM_ID = 9L;
    private static final UUID OWNER = UUID.randomUUID();

    private final ExamRepository examRepository = mock(ExamRepository.class);
    private final ExamEditLock editLock = mock(ExamEditLock.class);
    private final ExamAccessPolicy accessPolicy = mock(ExamAccessPolicy.class);
    private final GroupAuditService auditService = mock(GroupAuditService.class);
    private final ExamCacheService cacheService = mock(ExamCacheService.class);
    private final ExamMapper mapper = mock(ExamMapper.class);
    private final TransactionalOperator transactionalOperator = mock(TransactionalOperator.class);
    private final com.examportal.exam.structure.ExamStateRepository stateRepository = mock(com.examportal.exam.structure.ExamStateRepository.class);

    private ExamService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(transactionalOperator.transactional(any(Mono.class))).thenAnswer(call -> call.getArgument(0));
        when(accessPolicy.requireAccess(any(), anyString(), any(Exam.class))).thenAnswer(call -> Mono.just(call.getArgument(2)));
        when(accessPolicy.canManage(any(), anyString(), any(Exam.class))).thenReturn(true);
        when(examRepository.reschedule(anyLong(), any(), any(), anyString(), any(), anyBoolean(), any())).thenReturn(Mono.just(1));
        when(auditService.record(any(), anyString(), any(), any(), anyString())).thenReturn(Mono.empty());
        when(cacheService.invalidateExamCache(EXAM_ID)).thenReturn(Mono.empty());
        when(mapper.toResponse(any(Exam.class))).thenAnswer(call -> ExamResponse.builder()
                .id(EXAM_ID).status(((Exam) call.getArgument(0)).getStatus()).build());
        service = new ExamService(examRepository, mock(QuestionRepository.class), mock(QuestionOptionRepository.class),
                mock(ExamSessionRepository.class), mock(ExamAttemptRepository.class), editLock,
                mock(ExamManageRepository.class), mock(GroupMembershipRepository.class), mock(ExamDayClock.class),
                cacheService, mock(AnswerSubmissionService.class), accessPolicy, mock(UserGroupRepository.class),
                mock(ExamAudienceRepository.class), auditService, mock(ExamMediaService.class), mock(ExamValidator.class),
                mapper, transactionalOperator, stateRepository,
                new com.examportal.exam.structure.StructurePlanner(), mock(com.examportal.exam.structure.ExamStructureWriter.class),
                mock(ExamBlockRepository.class), new ExamFeatures(false, false));
        // A plain exam (one block, exam timer): the B12a publish gate lets it through
        when(stateRepository.load(EXAM_ID)).thenReturn(Mono.just(new com.examportal.exam.structure.ExamState(EXAM_ID, OWNER,
                "T", null, ExamStatus.ACTIVE, 30, LocalDateTime.now(), LocalDateTime.now().plusDays(1), null, false, false,
                false, 1, null, null, com.examportal.common.domain.entity.ExamAccessType.PUBLIC,
                com.examportal.common.domain.entity.TimingMode.EXAM_TIMER, false, true, true, List.of())));
    }

    private void examIs(ExamStatus status, boolean withAttempts) {
        Exam exam = Exam.builder().id(EXAM_ID).status(status).createdBy(OWNER).active(true).maxAttempts(1)
                .startTime(LocalDateTime.now().minusDays(3)).endTime(LocalDateTime.now().minusDays(2)).build();
        when(examRepository.findById(EXAM_ID)).thenReturn(Mono.just(exam));
        when(examRepository.lockById(EXAM_ID)).thenReturn(Mono.just(exam));
        when(editLock.hasSessions(EXAM_ID)).thenReturn(Mono.just(withAttempts));
    }

    private static RescheduleExamRequest window(boolean startLater) {
        RescheduleExamRequest request = new RescheduleExamRequest();
        if (startLater) {
            request.setStartTime(LocalDateTime.now().plusDays(1));
        } else {
            request.setStartTime(LocalDateTime.now().minusMinutes(5));
        }
        request.setEndTime(LocalDateTime.now().plusDays(2));
        return request;
    }

    @Test
    void finishedOrArchivedWithAttemptsToALaterStartIs409() {
        for (ExamStatus status : new ExamStatus[]{ExamStatus.COMPLETED, ExamStatus.ARCHIVED}) {
            examIs(status, true);
            StepVerifier.create(service.rescheduleExam(EXAM_ID, window(true), OWNER, "INSTRUCTOR"))
                    .expectErrorSatisfies(error -> {
                        assertThat(error).isInstanceOf(ApplicationException.class);
                        assertThat(((ApplicationException) error).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(((ApplicationException) error).getErrorCode()).isEqualTo("EXAM_HAS_SESSIONS");
                    })
                    .verify();
        }
        verify(examRepository, never()).reschedule(anyLong(), any(), any(), anyString(), any(), anyBoolean(), any());
    }

    @Test
    void finishedWithAttemptsReopenedAtOnceBecomesActive() {
        examIs(ExamStatus.COMPLETED, true);
        StepVerifier.create(service.rescheduleExam(EXAM_ID, window(false), OWNER, "INSTRUCTOR"))
                .expectNextCount(1)
                .verifyComplete();
        verify(examRepository).reschedule(eq(EXAM_ID), any(), any(), eq("ACTIVE"), any(), anyBoolean(), any());
        verify(editLock, never()).hasSessions(anyLong());
    }

    @Test
    void withoutAttemptsALaterStartStillMakesItScheduled() {
        for (ExamStatus status : new ExamStatus[]{ExamStatus.COMPLETED, ExamStatus.ARCHIVED}) {
            examIs(status, false);
            StepVerifier.create(service.rescheduleExam(EXAM_ID, window(true), OWNER, "ADMIN"))
                    .expectNextCount(1)
                    .verifyComplete();
        }
        verify(examRepository, org.mockito.Mockito.times(2))
                .reschedule(eq(EXAM_ID), any(), any(), eq("SCHEDULED"), any(), anyBoolean(), any());
    }

    @Test
    void anActiveExamWithAttemptsMayStillBePostponedItsQuestionsStayLocked() {
        // Unchanged: postponing a running exam is allowed; its questions stay protected by EXAM_HAS_SESSIONS on update
        examIs(ExamStatus.ACTIVE, true);
        StepVerifier.create(service.rescheduleExam(EXAM_ID, window(true), OWNER, "ADMIN"))
                .expectNextCount(1)
                .verifyComplete();
        verify(examRepository).reschedule(eq(EXAM_ID), any(), any(), eq("SCHEDULED"), any(), anyBoolean(), any());
    }
}
