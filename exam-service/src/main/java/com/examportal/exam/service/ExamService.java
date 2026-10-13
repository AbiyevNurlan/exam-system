package com.examportal.exam.service;

import com.examportal.common.domain.dto.*;
import com.examportal.common.domain.dto.group.ExamAudienceRequest;
import com.examportal.common.domain.dto.group.ExamAudienceResponse;
import com.examportal.common.domain.entity.*;
import com.examportal.exam.exception.ExamException;
import com.examportal.exam.exception.GroupException;
import com.examportal.exam.mapper.ExamMapper;
import com.examportal.exam.repository.*;
import com.examportal.exam.validator.ExamValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
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
    private final AnswerSubmissionService answerService;
    private final ExamAccessPolicy accessPolicy;
    private final UserGroupRepository groupRepository;
    private final ExamAudienceRepository audienceRepository;
    private final GroupAuditService auditService;
    private final ExamMediaService examMedia;
    private final ExamValidator validator;
    private final ExamMapper mapper;
    private final TransactionalOperator transactionalOperator;

    private static final int DEFAULT_BATCH_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;

    /**
     * Create a new exam with questions and options
     */
    public Mono<ExamResponse> createExam(CreateExamRequest request, UUID userId, String role) {
        log.info("Creating exam '{}' for user {}", request.getTitle(), userId);

        // PUBLIC unless the request says otherwise; a GROUP exam gets its audience in the same call
        ExamAccessType accessType = request.getAccessType() != null ? request.getAccessType() : ExamAccessType.PUBLIC;
        List<Long> groupIds = audienceGroupIds(accessType, request.getGroupIds());

        Mono<ExamResponse> creation = validator.validateCreateRequest(request)
                .then(requireValidAudience(accessType, groupIds))
                .then(examMedia.validate(request.getQuestions(), userId, role))
                .then(Mono.defer(() -> {
                    Exam exam = mapper.toEntity(request, userId);
                    exam.setAccessType(accessType);
                    return examRepository.save(exam);
                }))
                .flatMap(savedExam -> saveQuestionsAndOptions(savedExam.getId(), request.getQuestions())
                        .then(groupIds.isEmpty()
                                ? Mono.<Void>empty()
                                : writeAudience(savedExam.getId(), savedExam.getStatus(), ExamAccessType.PUBLIC,
                                        accessType, List.of(), groupIds, userId))
                        .then(Mono.just(savedExam)))
                .flatMap(this::loadExamWithQuestions);

        // @Transactional is not applied at runtime in this service, so the TransactionalOperator is the real
        // boundary: exam, questions, options, media links and audience are stored together or not at all.
        // The cache is filled after commit; media is added to the answer only, never to the cached exam.
        return transactionalOperator.transactional(creation)
                .flatMap(response -> cacheService.cacheExam(response).thenReturn(response))
                .flatMap(response -> examMedia.withMedia(response, true))
                .doOnSuccess(exam -> log.info("Exam created successfully: {}", exam.getId()));
    }

    /**
     * Update an existing exam
     */
    public Mono<ExamResponse> updateExam(Long examId, UpdateExamRequest request, UUID userId, String role) {
        log.info("Updating exam {} for user {}", examId, userId);

        // No accessType in the request = the audience stays as it is
        ExamAccessType newAccessType = request.getAccessType();
        List<Long> groupIds = audienceGroupIds(newAccessType, request.getGroupIds());

        Mono<ExamResponse> update = findAccessibleExam(examId, userId, role)
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
                        .then(newAccessType == null ? Mono.<Void>empty() : requireValidAudience(newAccessType, groupIds))
                        .then(examMedia.validate(request.getQuestions(), userId, role))
                        .thenReturn(exam))
                .flatMap(exam -> {
                    ExamAccessType oldAccessType = exam.getAccessType();
                    mapper.updateEntity(exam, request);
                    if (newAccessType == null) {
                        return examRepository.save(exam);
                    }
                    exam.setAccessType(newAccessType);
                    return examRepository.save(exam)
                            .flatMap(saved -> audienceRepository.findGroupIdsByExamId(examId).collectList()
                                    // An unchanged audience writes nothing, so it leaves no audit entry either
                                    .flatMap(oldGroupIds -> oldAccessType == newAccessType && oldGroupIds.equals(groupIds)
                                            ? Mono.<Void>empty()
                                            : writeAudience(examId, saved.getStatus(), oldAccessType, newAccessType,
                                                    oldGroupIds, groupIds, userId))
                                    .thenReturn(saved));
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
                .flatMap(response -> examMedia.withMedia(response, true))
                .doOnSuccess(exam -> log.info("Exam updated successfully: {}", examId));
    }

    /**
     * Delete an exam
     */
    public Mono<Void> deleteExam(Long examId, UUID userId, String role) {
        log.info("Deleting exam {} for user {}", examId, userId);

        return findAccessibleExam(examId, userId, role)
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

        // The exam row is always read, so the access check never relies on the cache; only the
        // questions and options come from it
        return findAccessibleExam(examId, userId, role)
                .flatMap(exam -> cacheService.getCachedExam(examId)
                        .switchIfEmpty(Mono.defer(() -> loadExamWithQuestions(exam)
                                .flatMap(response -> cacheService.cacheExam(response).thenReturn(response))))
                        .flatMap(full -> {
                            // The answer key and media preview URLs are only for ADMIN and the INSTRUCTOR who owns the exam
                            if (accessPolicy.canManage(userId, role, exam)) {
                                return examMedia.withMedia(full, true);
                            }
                            if (exam.getStatus() == ExamStatus.DRAFT) {
                                return Mono.error(ExamException.notFound(examId));
                            }
                            // Participants see which media a question has, but get no URL here
                            return examMedia.withMedia(mapper.toParticipantView(full), false);
                        }));
    }

    /**
     * Get exams created by a user
     */
    public Flux<ExamResponse> getMyExams(UUID userId, ExamStatus status) {
        log.debug("Getting exams for user {} with status {}", userId, status);

        Flux<Exam> exams = status != null
                ? examRepository.findByCreatedByAndStatus(userId, status)
                : examRepository.findByCreatedBy(userId);

        return exams.flatMap(exam -> loadExamWithQuestions(exam))
                .flatMap(response -> examMedia.withMedia(response, true));
    }

    /**
     * One page of the exams a participant may access and can still start or resume.
     * Which exams are listed is decided in SQL by the access policy; this only adds the attempt figures.
     */
    public Mono<PageResponse<ExamResponse>> getAvailableExams(UUID userId, String role, int page, int size) {
        log.debug("Getting available exams for user {}", userId);

        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        return accessPolicy.countAvailableExams(userId, role)
                .flatMap(total -> accessPolicy.findAvailableExams(userId, role, pageSize, (long) pageNumber * pageSize)
                        .concatMap(exam -> Mono.zip(
                                        sessionRepository.countByExamIdAndUserId(exam.getId(), userId),
                                        sessionRepository.findActiveSession(exam.getId(), userId)
                                                .map(active -> !active.isExpired())
                                                .defaultIfEmpty(false))
                                .map(tuple -> {
                                    // Same rule as startExam: every session counts as an attempt, and an
                                    // active session that still has time can always be resumed
                                    int remaining = Math.max(0, maxAttempts(exam) - tuple.getT1().intValue());
                                    boolean resumable = tuple.getT2();
                                    ExamResponse response = mapper.toResponse(exam);
                                    response.setRemainingAttempts(remaining);
                                    response.setCanStart(exam.canStart() && (remaining > 0 || resumable));
                                    return response;
                                }))
                        .collectList()
                        .map(rows -> PageResponse.of(rows, pageNumber, pageSize, total)));
    }

    /**
     * Start an exam session
     */
    public Mono<StartExamResponse> startExam(Long examId, UUID userId, String role, StartExamRequest request) {
        log.info("Starting exam {} for user {}", examId, userId);

        // Start always needs access to the exam, also when a session is already open: a user who lost
        // access finishes that session through the session endpoints, not through start
        return findAccessibleExam(examId, userId, role)
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
                // Someone else's session answers 404, same as an unknown id
                .flatMap(session -> accessPolicy.requireOwnSession(userId, session))
                .flatMap(session -> {
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
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        String statusFilter = status != null ? status.name() : null;

        return findAccessibleExam(examId, userId, role)
                .flatMap(exam -> {
                    if (!accessPolicy.canManage(userId, role, exam)) {
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

        return findAccessibleExam(examId, userId, role)
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
                .flatMap(response -> examMedia.withMedia(response, true))
                .doOnSuccess(exam -> log.info("Exam {} status updated to {}", examId, newStatus));
    }

    /**
     * Set who may take an exam: PUBLIC (everyone) or GROUP (members of the given groups).
     * Allowed for the owner and ADMIN in every exam status; changing a published exam takes effect
     * at once for new starts, while sessions already in progress can be finished.
     * This only stores the audience; who has access is decided by ExamAccessPolicy.
     */
    public Mono<ExamAudienceResponse> setAudience(Long examId, ExamAudienceRequest request, UUID userId, String role) {
        ExamAccessType accessType = request.getAccessType();
        List<Long> groupIds = audienceGroupIds(accessType, request.getGroupIds());

        Mono<ExamAudienceResponse> change = findAccessibleExam(examId, userId, role)
                .flatMap(exam -> {
                    if (!"ADMIN".equals(role) && !exam.getCreatedBy().equals(userId)) {
                        return Mono.error(ExamException.notOwner());
                    }
                    return requireValidAudience(accessType, groupIds).thenReturn(exam);
                })
                .flatMap(exam -> audienceRepository.findGroupIdsByExamId(examId).collectList()
                        .flatMap(oldGroupIds -> examRepository.updateAccessType(examId, accessType.name())
                                .then(writeAudience(examId, exam.getStatus(), exam.getAccessType(), accessType,
                                        oldGroupIds, groupIds, userId))
                                .thenReturn(ExamAudienceResponse.builder()
                                        .examId(examId)
                                        .accessType(accessType)
                                        .groupIds(groupIds)
                                        .build())));

        // Audience rows, access type and audit entry change together; the cached exam is dropped after commit
        return transactionalOperator.transactional(change)
                .flatMap(response -> cacheService.invalidateExamCache(examId).thenReturn(response))
                .doOnSuccess(response -> log.info("Exam {} audience set to {} {} by user {}",
                        examId, response.getAccessType(), response.getGroupIds(), userId));
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

    /**
     * Existing exam the user may access; an exam outside the user's audience is a 404 like an unknown one.
     * Whether the user may also change it is checked by the caller.
     */
    private Mono<Exam> findAccessibleExam(Long examId, UUID userId, String role) {
        return findExistingExam(examId)
                .flatMap(exam -> accessPolicy.requireAccess(userId, role, exam));
    }

    /**
     * The groups of an audience request: distinct and sorted for GROUP, none for PUBLIC (or no type)
     */
    private List<Long> audienceGroupIds(ExamAccessType accessType, List<Long> requested) {
        return accessType == ExamAccessType.GROUP && requested != null
                ? requested.stream().distinct().sorted().toList()
                : List.of();
    }

    /**
     * A GROUP audience needs at least one group, and every group must exist and be active
     */
    private Mono<Void> requireValidAudience(ExamAccessType accessType, List<Long> groupIds) {
        if (accessType == ExamAccessType.GROUP && groupIds.isEmpty()) {
            return Mono.error(GroupException.audienceGroupsRequired());
        }
        if (groupIds.isEmpty()) {
            return Mono.empty();
        }
        return groupRepository.countActiveByIdIn(groupIds)
                .flatMap(found -> found == groupIds.size()
                        ? Mono.<Void>empty()
                        : Mono.<Void>error(GroupException.audienceGroupNotFound()));
    }

    /**
     * Replace the audience rows of an exam and write the audit entry. The caller stores the access
     * type on the exam row and provides the transaction.
     */
    private Mono<Void> writeAudience(Long examId, ExamStatus status, ExamAccessType oldAccessType,
                                     ExamAccessType newAccessType, List<Long> oldGroupIds, List<Long> groupIds,
                                     UUID userId) {
        return audienceRepository.deleteByExamId(examId)
                .thenMany(Flux.fromIterable(groupIds)
                        .concatMap(groupId -> audienceRepository.add(examId, groupId)))
                .then(auditService.record(userId, GroupAuditService.EXAM_AUDIENCE_CHANGED, null, null,
                        "examId=" + examId + " status=" + status
                                + " accessType=" + oldAccessType + " -> " + newAccessType
                                + " groups=" + oldGroupIds + " -> " + groupIds));
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
                            .flatMap(savedQuestion -> examMedia.attachToQuestion(savedQuestion.getId(), dto)
                                    .then(Mono.defer(() -> {
                                        if (dto.getOptions() != null && !dto.getOptions().isEmpty()) {
                                            return saveOptions(savedQuestion.getId(), dto.getOptions());
                                        }
                                        return Mono.empty();
                                    })));
                })
                .then();
    }

    /**
     * Save options for a question
     */
    private Mono<Void> saveOptions(Long questionId, List<OptionDTO> options) {
        return Flux.fromIterable(options)
                .index()
                .concatMap(tuple -> {
                    OptionDTO dto = tuple.getT2();
                    dto.setOrderIndex(tuple.getT1().intValue());
                    return optionRepository.save(mapper.toEntity(dto, questionId))
                            .flatMap(savedOption -> examMedia.attachToOption(savedOption.getId(), dto));
                })
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
                })
                // A resumed session comes with what was already answered, so the screen can be restored
                .flatMap(response -> answerService.savedAnswers(session)
                        .map(saved -> {
                            response.setSavedAnswers(saved);
                            return response;
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
                    
                    // Media metadata only: the URL of each media is asked per session (SessionMediaService)
                    return Mono.zip(optionRepository.findByQuestionIdIn(questionIds)
                                            .collectMultimap(QuestionOption::getQuestionId),
                                    examMedia.loadIndex(exam.getId()))
                            .map(loaded -> {
                                Map<Long, Collection<QuestionOption>> optionsMap = loaded.getT1();
                                ExamMediaService.MediaIndex media = loaded.getT2();
                                List<QuestionResponse> responses = questions.stream()
                                        .map(q -> {
                                            // Text questions keep their expected answer as an option: never send it
                                            List<OptionResponse> options = mapper.isTextQuestion(q.getQuestionType())
                                                    ? null
                                                    : optionsMap.getOrDefault(q.getId(), List.of())
                                                    .stream()
                                                    .map(option -> {
                                                        OptionResponse response = mapper.toParticipantResponse(option);
                                                        response.setMedia(media.forOption(option.getId()));
                                                        return response;
                                                    })
                                                    .collect(Collectors.toList());

                                            // Shuffle options if configured
                                            if (Boolean.TRUE.equals(exam.getShuffleOptions())) {
                                                options = mapper.shuffleList(options);
                                            }
                                            
                                            QuestionResponse response = mapper.toParticipantResponse(q, options);
                                            response.setMedia(media.forQuestion(q.getId()));
                                            return response;
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
