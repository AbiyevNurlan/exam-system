package com.examportal.exam.service;

import com.examportal.common.domain.dto.*;
import com.examportal.common.domain.entity.*;
import com.examportal.exam.exception.ExamException;
import com.examportal.exam.mapper.ExamMapper;
import com.examportal.exam.repository.*;
import com.examportal.exam.validator.ExamValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Exam Service
 * Core business logic for exam management.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamService {

    private final ExamRepository examRepository;
    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final ExamSessionRepository sessionRepository;
    private final ExamCacheService cacheService;
    private final AnswerSubmissionService answerService;
    private final ExamValidator validator;
    private final ExamMapper mapper;
    private final TransactionalOperator transactionalOperator;

    private static final int DEFAULT_BATCH_SIZE = 10;
    private static final int MAX_RESULTS_PAGE_SIZE = 50;

    /**
     * Create a new exam with questions and options
     */
    public Mono<ExamResponse> createExam(CreateExamRequest request, UUID userId) {
        log.info("Creating exam '{}' for user {}", request.getTitle(), userId);

        Mono<ExamResponse> creation = validator.validateCreateRequest(request)
                .then(validator.validateNoSchedulingConflict(userId, request.getStartTime(),
                        request.getEndTime(), null))
                .then(Mono.defer(() -> {
                    Exam exam = mapper.toEntity(request, userId);
                    return examRepository.save(exam);
                }))
                .flatMap(savedExam -> saveQuestionsAndOptions(savedExam.getId(), request.getQuestions())
                        .then(Mono.just(savedExam)))
                .flatMap(this::loadExamWithQuestions);

        // @Transactional is not applied at runtime in this service, so the TransactionalOperator is the real
        // boundary: exam, questions and options are stored together or not at all. The cache is filled after commit.
        return transactionalOperator.transactional(creation)
                .flatMap(response -> cacheService.cacheExam(response).thenReturn(response))
                .doOnSuccess(exam -> log.info("Exam created successfully: {}", exam.getId()));
    }

    /**
     * Update an existing exam
     */
    public Mono<ExamResponse> updateExam(Long examId, UpdateExamRequest request, UUID userId) {
        log.info("Updating exam {} for user {}", examId, userId);

        Mono<ExamResponse> update = findExistingExam(examId)
                .flatMap(exam -> {
                    if (!exam.getCreatedBy().equals(userId)) {
                        return Mono.error(ExamException.notOwner());
                    }
                    if (!exam.canModify()) {
                        return Mono.error(ExamException.cannotModify());
                    }
                    return Mono.just(exam);
                })
                .flatMap(exam -> validator.validateUpdateRequest(request)
                        .then(validator.validateNoSchedulingConflict(userId, request.getStartTime(),
                                request.getEndTime(), examId))
                        .thenReturn(exam))
                .flatMap(exam -> {
                    mapper.updateEntity(exam, request);
                    return examRepository.save(exam);
                })
                .flatMap(exam -> deleteExistingQuestionsAndOptions(examId)
                        .then(saveQuestionsAndOptions(examId, request.getQuestions()))
                        .then(examRepository.updateTotalQuestions(examId, request.getQuestions().size()))
                        .thenReturn(exam))
                .flatMap(this::loadExamWithQuestions);

        // One transaction for the exam row and the replaced questions/options; the cache is refreshed after commit
        return transactionalOperator.transactional(update)
                .flatMap(response -> cacheService.invalidateExamCache(examId)
                        .then(cacheService.cacheExam(response))
                        .thenReturn(response))
                .doOnSuccess(exam -> log.info("Exam updated successfully: {}", examId));
    }

    /**
     * Delete an exam
     */
    public Mono<Void> deleteExam(Long examId, UUID userId) {
        log.info("Deleting exam {} for user {}", examId, userId);

        return findExistingExam(examId)
                .flatMap(exam -> {
                    if (!exam.getCreatedBy().equals(userId)) {
                        return Mono.error(ExamException.notOwner());
                    }
                    return sessionRepository.existsByExamId(examId)
                            .flatMap(hasAttempts -> {
                                if (hasAttempts) {
                                    return Mono.error(ExamException.cannotDelete());
                                }
                                return Mono.just(exam);
                            });
                })
                .flatMap(exam -> examRepository.softDelete(examId)
                        .then(cacheService.invalidateExamCache(examId)))
                .doOnSuccess(v -> log.info("Exam deleted successfully: {}", examId));
    }

    /**
     * Get exam by ID with questions
     */
    public Mono<ExamResponse> getExamById(Long examId, UUID userId, String role) {
        log.debug("Getting exam {} for user {}", examId, userId);

        return cacheService.getCachedExam(examId)
                .switchIfEmpty(findExistingExam(examId)
                        .flatMap(this::loadExamWithQuestions)
                        .flatMap(response -> cacheService.cacheExam(response).thenReturn(response)))
                .flatMap(full -> {
                    // The answer key is only for ADMIN and the INSTRUCTOR who owns the exam
                    boolean owner = "INSTRUCTOR".equals(role) && userId.equals(full.getCreatedBy());
                    if ("ADMIN".equals(role) || owner) {
                        return Mono.just(full);
                    }
                    if (full.getStatus() == ExamStatus.DRAFT) {
                        return Mono.error(ExamException.notFound(examId));
                    }
                    return Mono.just(mapper.toParticipantView(full));
                });
    }

    /**
     * Get exams created by a user
     */
    public Flux<ExamResponse> getMyExams(UUID userId, ExamStatus status) {
        log.debug("Getting exams for user {} with status {}", userId, status);

        Flux<Exam> exams = status != null
                ? examRepository.findByCreatedByAndStatus(userId, status)
                : examRepository.findByCreatedBy(userId);

        return exams.flatMap(exam -> loadExamWithQuestions(exam));
    }

    /**
     * Get available exams for participants
     */
    public Flux<ExamResponse> getAvailableExams(UUID userId) {
        log.debug("Getting available exams for user {}", userId);

        return examRepository.findAvailableExams()
                .flatMap(exam -> Mono.zip(
                                sessionRepository.countByExamIdAndUserId(exam.getId(), userId),
                                sessionRepository.findActiveSession(exam.getId(), userId)
                                        .map(active -> !active.isExpired())
                                        .defaultIfEmpty(false))
                        .map(tuple -> {
                            // Same rule as startExam: every session counts as an attempt, and an active
                            // session that still has time can always be resumed
                            int remaining = Math.max(0, maxAttempts(exam) - tuple.getT1().intValue());
                            boolean resumable = tuple.getT2();
                            ExamResponse response = mapper.toResponse(exam);
                            response.setRemainingAttempts(remaining);
                            response.setCanStart(exam.canStart() && (remaining > 0 || resumable));
                            return Tuples.of(response, remaining > 0 || resumable);
                        }))
                .filter(Tuple2::getT2)
                .map(Tuple2::getT1);
    }

    /**
     * Start an exam session
     */
    public Mono<StartExamResponse> startExam(Long examId, UUID userId, StartExamRequest request) {
        log.info("Starting exam {} for user {}", examId, userId);

        return findExistingExam(examId)
                .flatMap(exam -> {
                    if (!exam.canStart()) {
                        return Mono.error(ExamException.notActive());
                    }
                    // Idempotent: an active session for this user and exam is returned, never duplicated.
                    // One that ran out of time is never returned: it is completed through the normal
                    // completion path and the start continues as if there were none (it still counts as an attempt).
                    return sessionRepository.findActiveSession(examId, userId)
                            .flatMap(active -> active.isExpired()
                                    ? answerService.completeExpiredSession(active).then(Mono.<ExamSession>empty())
                                    : Mono.just(active))
                            .switchIfEmpty(Mono.defer(() -> openSession(exam, userId, request)))
                            .flatMap(session -> toStartResponse(exam, session));
                })
                .doOnSuccess(response -> log.info("Exam started: session {}", response.getSessionId()));
    }

    /**
     * Get next batch of questions for a session
     */
    public Mono<List<QuestionResponse>> getNextQuestions(String sessionId, int batch, UUID userId) {
        log.debug("Getting questions batch {} for session {}", batch, sessionId);

        return sessionRepository.findBySessionId(sessionId)
                .switchIfEmpty(Mono.error(ExamException.sessionNotFound(sessionId)))
                .flatMap(session -> {
                    // Someone else's session answers 404, same as an unknown id
                    if (!session.getUserId().equals(userId)) {
                        return Mono.error(ExamException.sessionNotFound(sessionId));
                    }
                    if (!session.isActive()) {
                        return Mono.error(ExamException.sessionAlreadyCompleted());
                    }
                    return cacheService.getCachedQuestions(sessionId, batch, DEFAULT_BATCH_SIZE)
                            .collectList();
                });
    }

    /**
     * One page of an exam's sessions for ADMIN or the INSTRUCTOR who owns the exam
     */
    public Mono<PageResponse<SessionSummaryResponse>> getExamResults(Long examId, UUID userId, String role,
                                                                     SessionStatus status, int page, int size) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_RESULTS_PAGE_SIZE);
        String statusFilter = status != null ? status.name() : null;

        return findExistingExam(examId)
                .flatMap(exam -> {
                    boolean owner = "INSTRUCTOR".equals(role) && userId.equals(exam.getCreatedBy());
                    if (!"ADMIN".equals(role) && !owner) {
                        return Mono.error(ExamException.notOwner());
                    }
                    return sessionRepository.countByExamIdAndOptionalStatus(examId, statusFilter);
                })
                .flatMap(total -> sessionRepository.findPageByExamId(examId, statusFilter, pageSize,
                                (long) pageNumber * pageSize)
                        .map(session -> SessionSummaryResponse.builder()
                                .sessionId(session.getSessionId())
                                .userId(session.getUserId())
                                .status(session.getStatus())
                                .score(session.getScore())
                                .percentage(session.getPercentage())
                                .passed(session.getPassed())
                                .submittedAt(session.getSubmittedAt())
                                .build())
                        .collectList()
                        .map(rows -> PageResponse.of(rows, pageNumber, pageSize, total)));
    }

    /**
     * Update exam status
     */
    public Mono<ExamResponse> updateExamStatus(Long examId, ExamStatus newStatus, UUID userId, String role) {
        log.info("Updating exam {} status to {} by user {}", examId, newStatus, userId);

        return findExistingExam(examId)
                .flatMap(exam -> {
                    // Publishing and archiving: the owner, or any ADMIN
                    if (!"ADMIN".equals(role) && !exam.getCreatedBy().equals(userId)) {
                        return Mono.error(ExamException.notOwner());
                    }
                    return Mono.just(exam);
                })
                .flatMap(exam -> examRepository.updateStatus(examId, newStatus.name())
                        .then(examRepository.findById(examId)))
                .flatMap(this::loadExamWithQuestions)
                .flatMap(response -> cacheService.invalidateExamCache(examId)
                        .then(cacheService.cacheExam(response))
                        .thenReturn(response))
                .doOnSuccess(exam -> log.info("Exam {} status updated to {}", examId, newStatus));
    }

    // ==================== Private Helper Methods ====================

    /**
     * Exam by id. A soft-deleted exam (active = false) is treated as not existing.
     */
    private Mono<Exam> findExistingExam(Long examId) {
        return examRepository.findById(examId)
                .filter(exam -> !Boolean.FALSE.equals(exam.getActive()))
                .switchIfEmpty(Mono.error(ExamException.notFound(examId)));
    }

    private int maxAttempts(Exam exam) {
        return exam.getMaxAttempts() != null ? exam.getMaxAttempts() : 1;
    }

    /**
     * Save questions and their options
     */
    private Mono<Void> saveQuestionsAndOptions(Long examId, List<QuestionDTO> questions) {
        return Flux.fromIterable(questions)
                .index()
                // Sequential: these writes share the one connection of the surrounding transaction
                .concatMap(tuple -> {
                    int index = tuple.getT1().intValue();
                    QuestionDTO dto = tuple.getT2();
                    dto.setOrderIndex(index);
                    
                    Question question = mapper.toEntity(dto, examId);
                    return questionRepository.save(question)
                            .flatMap(savedQuestion -> {
                                if (dto.getOptions() != null && !dto.getOptions().isEmpty()) {
                                    return saveOptions(savedQuestion.getId(), dto.getOptions());
                                }
                                return Mono.empty();
                            });
                })
                .then();
    }

    /**
     * Save options for a question
     */
    private Mono<Void> saveOptions(Long questionId, List<OptionDTO> options) {
        return Flux.fromIterable(options)
                .index()
                .map(tuple -> {
                    OptionDTO dto = tuple.getT2();
                    dto.setOrderIndex(tuple.getT1().intValue());
                    return mapper.toEntity(dto, questionId);
                })
                .concatMap(optionRepository::save)
                .then();
    }

    /**
     * Delete existing questions and options for an exam
     */
    private Mono<Void> deleteExistingQuestionsAndOptions(Long examId) {
        return questionRepository.findByExamIdOrderByOrderIndex(examId)
                .concatMap(question -> optionRepository.deleteByQuestionId(question.getId()))
                .then(questionRepository.deleteByExamId(examId));
    }

    /**
     * Load exam with questions and options
     */
    private Mono<ExamResponse> loadExamWithQuestions(Exam exam) {
        return questionRepository.findActiveByExamIdOrderByOrderIndex(exam.getId())
                .collectList()
                .flatMap(questions -> {
                    if (questions.isEmpty()) {
                        return Mono.just(mapper.toResponse(exam, List.of()));
                    }
                    
                    List<Long> questionIds = questions.stream()
                            .map(Question::getId)
                            .collect(Collectors.toList());
                    
                    return optionRepository.findByQuestionIdIn(questionIds)
                            .collectMultimap(QuestionOption::getQuestionId)
                            .map(optionsMap -> {
                                List<QuestionResponse> questionResponses = questions.stream()
                                        .map(q -> {
                                            List<OptionResponse> options = optionsMap.getOrDefault(q.getId(), List.of())
                                                    .stream()
                                                    .map(mapper::toResponse)
                                                    .sorted(Comparator.comparing(OptionResponse::getOrderIndex))
                                                    .collect(Collectors.toList());
                                            return mapper.toResponse(q, options);
                                        })
                                        .collect(Collectors.toList());
                                return mapper.toResponse(exam, questionResponses);
                            });
                });
    }

    /**
     * Create an exam session
     */
    private Mono<ExamSession> openSession(Exam exam, UUID userId, StartExamRequest request) {
        String sessionId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endTime = now.plusMinutes(exam.getDurationMinutes());

        // Ensure end time doesn't exceed exam end time
        if (endTime.isAfter(exam.getEndTime())) {
            endTime = exam.getEndTime();
        }

        // The attempt limit and the single active session are both decided by this one statement.
        // Whatever it did, the active session (ours, or the one a concurrent start created) is the answer;
        // none at all means the attempt limit refused the insert.
        return sessionRepository.insertIfAttemptsRemain(sessionId, exam.getId(), userId, now, endTime,
                        request.getIpAddress(), request.getBrowserInfo(), maxAttempts(exam))
                .flatMap(inserted -> sessionRepository.findActiveSession(exam.getId(), userId))
                .switchIfEmpty(Mono.error(ExamException.maxAttemptsReached()));
    }

    /**
     * Build the start response for a session, reusing its cached questions when present
     */
    private Mono<StartExamResponse> toStartResponse(Exam exam, ExamSession session) {
        return cacheService.getAllCachedQuestions(session.getSessionId())
                .flatMap(cached -> cached.isEmpty()
                        ? prepareQuestionsForSession(exam, session.getSessionId())
                        : Mono.just(cached))
                .map(questions -> {
                    int totalBatches = (int) Math.ceil((double) questions.size() / DEFAULT_BATCH_SIZE);
                    List<QuestionResponse> firstBatch = questions.subList(0,
                            Math.min(DEFAULT_BATCH_SIZE, questions.size()));

                    return StartExamResponse.builder()
                            .sessionId(session.getSessionId())
                            .examId(exam.getId())
                            .examTitle(exam.getTitle())
                            .startTime(session.getStartTime())
                            .endTime(session.getEndTime())
                            .durationMinutes(exam.getDurationMinutes())
                            .totalQuestions(questions.size())
                            .currentBatch(0)
                            .totalBatches(totalBatches)
                            .questions(firstBatch)
                            .build();
                });
    }

    /**
     * Prepare and cache questions for a session
     */
    private Mono<List<QuestionResponse>> prepareQuestionsForSession(Exam exam, String sessionId) {
        return questionRepository.findActiveByExamIdOrderByOrderIndex(exam.getId())
                .collectList()
                .flatMap(questions -> {
                    List<Long> questionIds = questions.stream()
                            .map(Question::getId)
                            .collect(Collectors.toList());
                    
                    return optionRepository.findByQuestionIdIn(questionIds)
                            .collectMultimap(QuestionOption::getQuestionId)
                            .map(optionsMap -> {
                                List<QuestionResponse> responses = questions.stream()
                                        .map(q -> {
                                            // Text questions keep their expected answer as an option: never send it
                                            List<OptionResponse> options = mapper.isTextQuestion(q.getQuestionType())
                                                    ? null
                                                    : optionsMap.getOrDefault(q.getId(), List.of())
                                                    .stream()
                                                    .map(mapper::toParticipantResponse)
                                                    .collect(Collectors.toList());

                                            // Shuffle options if configured
                                            if (Boolean.TRUE.equals(exam.getShuffleOptions())) {
                                                options = mapper.shuffleList(options);
                                            }
                                            
                                            return mapper.toParticipantResponse(q, options);
                                        })
                                        .collect(Collectors.toList());
                                
                                // Shuffle questions if configured
                                if (Boolean.TRUE.equals(exam.getShuffleQuestions())) {
                                    responses = mapper.shuffleList(responses);
                                }
                                
                                return responses;
                            });
                })
                .flatMap(questions -> cacheService.cacheSessionQuestions(sessionId, questions, 
                        exam.getDurationMinutes()).thenReturn(questions));
    }
}
