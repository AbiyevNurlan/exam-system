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
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
    private final ExamValidator validator;
    private final ExamMapper mapper;

    private static final int DEFAULT_BATCH_SIZE = 10;
    private static final int MAX_RESULTS_PAGE_SIZE = 50;

    /**
     * Create a new exam with questions and options
     */
    @Transactional
    public Mono<ExamResponse> createExam(CreateExamRequest request, Long userId) {
        log.info("Creating exam '{}' for user {}", request.getTitle(), userId);

        return validator.validateCreateRequest(request)
                .then(validator.validateNoSchedulingConflict(userId, request.getStartTime(), 
                        request.getEndTime(), null))
                .then(Mono.defer(() -> {
                    Exam exam = mapper.toEntity(request, userId);
                    return examRepository.save(exam);
                }))
                .flatMap(savedExam -> saveQuestionsAndOptions(savedExam.getId(), request.getQuestions())
                        .then(Mono.just(savedExam)))
                .flatMap(this::loadExamWithQuestions)
                .flatMap(response -> cacheService.cacheExam(response).thenReturn(response))
                .doOnSuccess(exam -> log.info("Exam created successfully: {}", exam.getId()))
                .doOnError(err -> log.error("Failed to create exam: {}", err.getMessage()));
    }

    /**
     * Update an existing exam
     */
    @Transactional
    public Mono<ExamResponse> updateExam(Long examId, UpdateExamRequest request, Long userId) {
        log.info("Updating exam {} for user {}", examId, userId);

        return examRepository.findById(examId)
                .switchIfEmpty(Mono.error(ExamException.notFound(examId)))
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
                .flatMap(this::loadExamWithQuestions)
                .flatMap(response -> cacheService.invalidateExamCache(examId)
                        .then(cacheService.cacheExam(response))
                        .thenReturn(response))
                .doOnSuccess(exam -> log.info("Exam updated successfully: {}", examId))
                .doOnError(err -> log.error("Failed to update exam {}: {}", examId, err.getMessage()));
    }

    /**
     * Delete an exam
     */
    @Transactional
    public Mono<Void> deleteExam(Long examId, Long userId) {
        log.info("Deleting exam {} for user {}", examId, userId);

        return examRepository.findById(examId)
                .switchIfEmpty(Mono.error(ExamException.notFound(examId)))
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
                .doOnSuccess(v -> log.info("Exam deleted successfully: {}", examId))
                .doOnError(err -> log.error("Failed to delete exam {}: {}", examId, err.getMessage()));
    }

    /**
     * Get exam by ID with questions
     */
    public Mono<ExamResponse> getExamById(Long examId, Long userId, String role) {
        log.debug("Getting exam {} for user {}", examId, userId);

        return cacheService.getCachedExam(examId)
                .switchIfEmpty(examRepository.findById(examId)
                        .switchIfEmpty(Mono.error(ExamException.notFound(examId)))
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
    public Flux<ExamResponse> getMyExams(Long userId, ExamStatus status) {
        log.debug("Getting exams for user {} with status {}", userId, status);

        Flux<Exam> exams = status != null
                ? examRepository.findByCreatedByAndStatus(userId, status)
                : examRepository.findByCreatedBy(userId);

        return exams.flatMap(exam -> loadExamWithQuestions(exam));
    }

    /**
     * Get available exams for participants
     */
    public Flux<ExamResponse> getAvailableExams(Long userId) {
        log.debug("Getting available exams for user {}", userId);

        return examRepository.findAvailableExams()
                .flatMap(exam -> {
                    // Check remaining attempts
                    return sessionRepository.countCompletedAttempts(exam.getId(), userId)
                            .map(attempts -> {
                                ExamResponse response = mapper.toResponse(exam);
                                int remaining = exam.getMaxAttempts() - attempts.intValue();
                                response.setRemainingAttempts(Math.max(0, remaining));
                                response.setCanStart(remaining > 0 && exam.canStart());
                                return response;
                            });
                })
                .filter(exam -> exam.getRemainingAttempts() > 0);
    }

    /**
     * Start an exam session
     */
    @Transactional
    public Mono<StartExamResponse> startExam(Long examId, Long userId, StartExamRequest request) {
        log.info("Starting exam {} for user {}", examId, userId);

        return examRepository.findById(examId)
                .switchIfEmpty(Mono.error(ExamException.notFound(examId)))
                .flatMap(exam -> {
                    if (!exam.canStart()) {
                        return Mono.error(ExamException.notActive());
                    }
                    return sessionRepository.existsByExamIdAndUserIdAndStatus(examId, userId, SessionStatus.IN_PROGRESS)
                            .flatMap(hasActive -> {
                                if (hasActive) {
                                    return Mono.error(ExamException.activeSessionExists());
                                }
                                return Mono.just(exam);
                            });
                })
                .flatMap(exam -> sessionRepository.countCompletedAttempts(examId, userId)
                        .flatMap(attempts -> {
                            if (attempts >= exam.getMaxAttempts()) {
                                return Mono.error(ExamException.maxAttemptsReached());
                            }
                            return Mono.just(exam);
                        }))
                .flatMap(exam -> createSession(exam, userId, request))
                .doOnSuccess(response -> log.info("Exam started: session {}", response.getSessionId()))
                .doOnError(err -> log.error("Failed to start exam {}: {}", examId, err.getMessage()));
    }

    /**
     * Get next batch of questions for a session
     */
    public Mono<List<QuestionResponse>> getNextQuestions(String sessionId, int batch, Long userId) {
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
    public Mono<PageResponse<SessionSummaryResponse>> getExamResults(Long examId, Long userId, String role,
                                                                     int page, int size) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_RESULTS_PAGE_SIZE);

        return examRepository.findById(examId)
                .switchIfEmpty(Mono.error(ExamException.notFound(examId)))
                .flatMap(exam -> {
                    boolean owner = "INSTRUCTOR".equals(role) && userId.equals(exam.getCreatedBy());
                    if (!"ADMIN".equals(role) && !owner) {
                        return Mono.error(ExamException.notOwner());
                    }
                    return sessionRepository.countByExamId(examId);
                })
                .flatMap(total -> sessionRepository.findPageByExamId(examId, pageSize, (long) pageNumber * pageSize)
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
    @Transactional
    public Mono<ExamResponse> updateExamStatus(Long examId, ExamStatus newStatus, Long userId) {
        log.info("Updating exam {} status to {} by user {}", examId, newStatus, userId);

        return examRepository.findById(examId)
                .switchIfEmpty(Mono.error(ExamException.notFound(examId)))
                .flatMap(exam -> {
                    if (!exam.getCreatedBy().equals(userId)) {
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
                .doOnSuccess(exam -> log.info("Exam {} status updated to {}", examId, newStatus))
                .doOnError(err -> log.error("Failed to update exam {} status: {}", examId, err.getMessage()));
    }

    // ==================== Private Helper Methods ====================

    /**
     * Save questions and their options
     */
    private Mono<Void> saveQuestionsAndOptions(Long examId, List<QuestionDTO> questions) {
        return Flux.fromIterable(questions)
                .index()
                .flatMap(tuple -> {
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
                .flatMap(optionRepository::save)
                .then();
    }

    /**
     * Delete existing questions and options for an exam
     */
    private Mono<Void> deleteExistingQuestionsAndOptions(Long examId) {
        return questionRepository.findByExamIdOrderByOrderIndex(examId)
                .flatMap(question -> optionRepository.deleteByQuestionId(question.getId()))
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
    private Mono<StartExamResponse> createSession(Exam exam, Long userId, StartExamRequest request) {
        String sessionId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endTime = now.plusMinutes(exam.getDurationMinutes());
        
        // Ensure end time doesn't exceed exam end time
        if (endTime.isAfter(exam.getEndTime())) {
            endTime = exam.getEndTime();
        }
        
        LocalDateTime finalEndTime = endTime;
        
        ExamSession session = ExamSession.builder()
                .sessionId(sessionId)
                .examId(exam.getId())
                .userId(userId)
                .startTime(now)
                .endTime(finalEndTime)
                .status(SessionStatus.IN_PROGRESS)
                .ipAddress(request.getIpAddress())
                .browserInfo(request.getBrowserInfo())
                .createdAt(now)
                .updatedAt(now)
                .build();

        return sessionRepository.save(session)
                .flatMap(savedSession -> prepareQuestionsForSession(exam, sessionId)
                        .map(questions -> {
                            int totalBatches = (int) Math.ceil((double) questions.size() / DEFAULT_BATCH_SIZE);
                            List<QuestionResponse> firstBatch = questions.subList(0, 
                                    Math.min(DEFAULT_BATCH_SIZE, questions.size()));
                            
                            return StartExamResponse.builder()
                                    .sessionId(sessionId)
                                    .examId(exam.getId())
                                    .examTitle(exam.getTitle())
                                    .startTime(now)
                                    .endTime(finalEndTime)
                                    .durationMinutes(exam.getDurationMinutes())
                                    .totalQuestions(questions.size())
                                    .currentBatch(0)
                                    .totalBatches(totalBatches)
                                    .questions(firstBatch)
                                    .build();
                        }));
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
