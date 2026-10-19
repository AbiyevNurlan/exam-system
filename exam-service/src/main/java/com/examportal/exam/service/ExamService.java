package com.examportal.exam.service;

import com.examportal.common.config.UtcDateTimeModule;
import com.examportal.common.domain.dto.*;
import com.examportal.common.domain.dto.group.ExamAudiencePreviewResponse;
import com.examportal.common.domain.dto.group.ExamAudienceRequest;
import com.examportal.common.domain.dto.group.ExamAudienceResponse;
import com.examportal.common.domain.dto.history.ExamHistoryDtos;
import com.examportal.common.domain.entity.*;
import com.examportal.common.exception.ValidationException;
import com.examportal.exam.exception.ExamException;
import com.examportal.exam.exception.GroupException;
import com.examportal.exam.mapper.ExamMapper;
import com.examportal.exam.repository.*;
import com.examportal.exam.history.ChangeContext;
import com.examportal.exam.history.ExamDiff;
import com.examportal.exam.history.ExamHistoryService;
import com.examportal.exam.history.ExamSnapshotService;
import com.examportal.exam.structure.ExamState;
import com.examportal.exam.structure.ExamStateRepository;
import com.examportal.exam.structure.ExamStructureWriter;
import com.examportal.exam.structure.StructurePlanner;
import com.examportal.exam.validator.ExamValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final ExamAttemptRepository attemptRepository;
    private final ExamEditLock editLock;
    private final ExamManageRepository manageRepository;
    private final GroupMembershipRepository membershipRepository;
    private final ExamDayClock dayClock;
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
    // B12a: blocks and questions are read and written in place
    private final ExamStateRepository stateRepository;
    private final StructurePlanner planner;
    private final ExamStructureWriter structureWriter;
    private final ExamBlockRepository blockRepository;
    private final ExamFeatures features;
    // B12a: every change of an exam is written to its history in the transaction of the change
    private final ExamHistoryService history;
    // B12a: every publication stores the whole exam as a new version with its SHA-256
    private final ExamSnapshotService snapshots;
    // B12a: the same check for GET /readiness and for publishing
    private final ExamReadinessService readiness;

    private static final int DEFAULT_BATCH_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;
    // As many as the audience request allows (ExamAudienceRequest)
    private static final int MAX_AUDIENCE_GROUPS = 50;
    private static final int MIN_SEARCH_LENGTH = 2;
    private static final int MAX_SEARCH_LENGTH = 100;
    // A draft is not published yet (normal update); CANCELLED is not set by any endpoint
    private static final Set<ExamStatus> RESCHEDULABLE = EnumSet.of(ExamStatus.SCHEDULED, ExamStatus.ACTIVE,
            ExamStatus.COMPLETED, ExamStatus.ARCHIVED);

    /**
     * Create a new exam with questions and options
     */
    public Mono<ExamResponse> createExam(CreateExamRequest request, UUID userId, String role) {
        return createExam(request, ChangeContext.of(userId, role));
    }

    /**
     * Create a new exam; the history gets EXAM_CREATED in the same transaction
     */
    public Mono<ExamResponse> createExam(CreateExamRequest request, ChangeContext change) {
        UUID userId = change.actorId();
        String role = change.role();
        log.info("Creating exam '{}' for user {}", request.getTitle(), userId);

        // PUBLIC unless the request says otherwise; a GROUP exam gets its audience in the same call
        ExamAccessType accessType = request.getAccessType() != null ? request.getAccessType() : ExamAccessType.PUBLIC;
        List<Long> groupIds = audienceGroupIds(accessType, request.getGroupIds());

        TimingMode mode = request.getTimingMode() != null ? request.getTimingMode() : TimingMode.EXAM_TIMER;
        List<QuestionDTO> questions = request.getQuestions() != null ? request.getQuestions() : List.of();

        Mono<ExamResponse> creation = validator.validateCreateRequest(request)
                .then(Mono.fromCallable(() -> planner.forCreate(request.getBlocks(), questions, null,
                        Boolean.TRUE.equals(request.getShuffleQuestions()))))
                .flatMap(plan -> validator.validateTiming(mode, request.getDurationMinutes(), request.getStartTime(),
                                request.getEndTime(), plan)
                        .flatMap(duration -> requireValidAudience(accessType, groupIds, userId, role, List.of())
                                .then(examMedia.validate(plan.questions(), userId, role, null))
                                .then(Mono.defer(() -> {
                                    Exam exam = mapper.toEntity(request, userId);
                                    exam.setAccessType(accessType);
                                    // With block timers: the block limits plus the breaks
                                    exam.setDurationMinutes(duration);
                                    exam.setTotalQuestions(plan.questionCount());
                                    return examRepository.save(exam);
                                })))
                        // The database gave the new exam its first block; the first block of the plan takes it
                        .flatMap(savedExam -> stateRepository.load(savedExam.getId())
                                .flatMap(created -> structureWriter.apply(savedExam.getId(), created,
                                        plan.withFirstBlockId(created.blocks().get(0).id())))
                                .then(groupIds.isEmpty()
                                        ? Mono.<Void>empty()
                                        : writeAudience(savedExam.getId(), savedExam.getStatus(), ExamAccessType.PUBLIC,
                                                accessType, List.of(), groupIds, userId))
                                .then(Mono.defer(() -> stateRepository.load(savedExam.getId())
                                        .flatMap(created -> history.record(savedExam.getId(), userId, null, change,
                                                ExamDiff.created(created)))))
                                .then(Mono.just(savedExam))))
                .flatMap(this::loadExamWithQuestions);

        // @Transactional is not applied at runtime in this service, so the TransactionalOperator is the real
        // boundary: exam, questions, options, media links and audience are stored together or not at all.
        // The cache is filled after commit; media is added to the answer only, never to the cached exam.
        return transactionalOperator.transactional(creation)
                .flatMap(response -> cacheService.cacheExam(response).thenReturn(response))
                .flatMap(response -> examMedia.withMedia(response, true))
                .flatMap(this::withLastEdits)
                .doOnSuccess(exam -> log.info("Exam created successfully: {}", exam.getId()));
    }

    /**
     * Update an existing exam
     */
    public Mono<ExamResponse> updateExam(Long examId, UpdateExamRequest request, UUID userId, String role) {
        return updateExam(examId, request, ChangeContext.of(userId, role));
    }

    /**
     * Update an existing exam in place; every change is a history row of one save (saveId), in the same transaction
     */
    public Mono<ExamResponse> updateExam(Long examId, UpdateExamRequest request, ChangeContext change) {
        UUID userId = change.actorId();
        String role = change.role();
        log.info("Updating exam {} for user {}", examId, userId);

        // No accessType in the request = the audience stays as it is
        ExamAccessType newAccessType = request.getAccessType();
        List<Long> groupIds = audienceGroupIds(newAccessType, request.getGroupIds());

        Mono<ExamResponse> update = findAccessibleExam(examId, userId, role)
                .flatMap(exam -> {
                    // ADMIN: every exam; INSTRUCTOR: only the own one
                    if (!accessPolicy.canManage(userId, role, exam)) {
                        return Mono.error(ExamException.notOwner());
                    }
                    return Mono.just(exam);
                })
                // The exam row stays locked until commit, so no session can be opened while the questions are
                // replaced. Whether one exists is asked only after the lock (a new snapshot): a session whose
                // start committed meanwhile is seen. An exam with attempts keeps what decided their results.
                .flatMap(checked -> editLock.lockForEdit(examId))
                .switchIfEmpty(Mono.error(ExamException.notFound(examId)))
                .flatMap(locked -> editLock.hasSessions(examId))
                .flatMap(taken -> taken ? Mono.<Exam>error(ExamException.hasSessions()) : findExistingExam(examId))
                .flatMap(exam -> exam.canModify() ? Mono.just(exam) : Mono.<Exam>error(ExamException.cannotModify()))
                .flatMap(exam -> validator.validateUpdateRequest(request, timingModeAfter(exam, request))
                        .then(newAccessType == null ? Mono.<Void>empty() : audienceRepository.findGroupIdsByExamId(examId).collectList()
                                .flatMap(stored -> requireValidAudience(newAccessType, groupIds, userId, role, stored)))
                        .then(examMedia.validate(requestQuestions(request.getBlocks(), request.getQuestions()), userId, role, examId))
                        .thenReturn(exam))
                // The stored structure, read under the row lock: questions, options and blocks are changed in
                // place (ids stay), not deleted and created again
                .flatMap(exam -> stateRepository.load(examId)
                        .flatMap(before -> Mono.fromCallable(() -> planner.forUpdate(request.getBlocks(),
                                        request.getQuestions() != null ? request.getQuestions() : List.of(), before,
                                        Boolean.TRUE.equals(request.getShuffleQuestions())))
                                .flatMap(plan -> validator.validateTiming(timingModeAfter(exam, request), request.getDurationMinutes(),
                                                request.getStartTime(), request.getEndTime(), plan)
                                        .flatMap(duration -> saveExamRow(exam, request, newAccessType, groupIds, userId, duration))
                                        .flatMap(saved -> structureWriter.apply(examId, before, plan)
                                                .then(examRepository.updateTotalQuestions(examId, plan.questionCount()))
                                                // The exam as it is now, compared with how it was: the history of this save
                                                .then(Mono.defer(() -> stateRepository.load(examId)))
                                                .flatMap(after -> (saved.getStatus() == ExamStatus.SCHEDULED
                                                        // A SCHEDULED exam is published: it may not get rules participants cannot get yet
                                                        ? requireBlockRulesAvailable(after)
                                                        : Mono.<Void>empty())
                                                        .then(history.record(examId, before.createdBy(), before.status(), change,
                                                                ExamDiff.update(before, after))))
                                                .then(auditForeignExam(userId, saved, GroupAuditService.EXAM_UPDATED_BY_ADMIN))
                                                .thenReturn(saved)))))
                .flatMap(this::loadExamWithQuestions);

        // One transaction for the exam row and the replaced questions/options; the cache is refreshed after commit
        return transactionalOperator.transactional(update)
                .flatMap(response -> cacheService.invalidateExamCache(examId)
                        .then(cacheService.cacheExam(response))
                        .thenReturn(response))
                .flatMap(response -> examMedia.withMedia(response, true))
                .flatMap(this::withLastEdits)
                .doOnSuccess(exam -> log.info("Exam updated successfully: {}", examId));
    }

    /**
     * Delete an exam for good. Only an exam nobody has started can go: with a single session it is
     * refused (EXAM_CANNOT_DELETE) and nothing is removed; such an exam is archived instead, which
     * keeps every attempt and result.
     */
    public Mono<Void> deleteExam(Long examId, UUID userId, String role) {
        return deleteExam(examId, ChangeContext.of(userId, role));
    }

    /**
     * Delete an exam; the history keeps a DELETE_ATTEMPTED row either way: with the whole exam when it went (in
     * the delete's own transaction), or REFUSED_HAS_SESSIONS when it had attempts (in a transaction of its own,
     * because the refusal rolls the delete back). The history itself is never deleted.
     */
    public Mono<Void> deleteExam(Long examId, ChangeContext change) {
        UUID userId = change.actorId();
        String role = change.role();
        log.info("Deleting exam {} for user {}", examId, userId);

        return findAccessibleExam(examId, userId, role)
                .flatMap(exam -> {
                    // ADMIN: every exam; INSTRUCTOR: only the own one
                    if (!accessPolicy.canManage(userId, role, exam)) {
                        return Mono.error(ExamException.notOwner());
                    }
                    return Mono.just(exam);
                })
                // "No session" is decided by the DELETE itself. The exam, everything that belongs to it
                // and the audit entry go together or not at all.
                .flatMap(exam -> stateRepository.load(examId).flatMap(state -> transactionalOperator.transactional(
                                examRepository.deleteIfNoSessions(examId)
                                        .flatMap(deleted -> deleted == 1
                                                ? auditDeletedExam(userId, exam)
                                                        .then(history.record(examId, exam.getCreatedBy(), exam.getStatus(), change,
                                                                List.of(ExamDiff.deleteAttempt(state, "DELETED"))))
                                                : Mono.<Void>error(ExamException.cannotDelete())))
                        // A session opened while the delete ran: the foreign key refused it
                        .onErrorMap(DataIntegrityViolationException.class, e -> ExamException.cannotDelete())
                        .onErrorResume(error -> error instanceof ExamException refused && "EXAM_CANNOT_DELETE".equals(refused.getErrorCode())
                                ? transactionalOperator.transactional(history.record(examId, exam.getCreatedBy(), exam.getStatus(),
                                                change, List.of(ExamDiff.deleteAttempt(state, "REFUSED_HAS_SESSIONS"))))
                                        .then(Mono.error(error))
                                : Mono.error(error))
                        .then(cacheService.invalidateExamCache(examId))))
                .doOnSuccess(v -> log.info("Exam deleted successfully: {}", examId));
    }

    /**
     * B12a: a new DRAFT copy of an exam for the caller: blocks, questions, options, the answer key, settings and
     * media links (the same media assets), titled "<title> (nüsxə)". Not copied: the audience groups (a GROUP copy
     * starts with none, so nobody but its owner and ADMIN can take it until groups are set), sessions, results and
     * the history of the source. The copy's history starts with CLONED_FROM. ADMIN or the owning INSTRUCTOR.
     */
    public Mono<ExamResponse> cloneExam(Long examId, ChangeContext change) {
        UUID userId = change.actorId();
        String role = change.role();
        Mono<ExamResponse> cloning = findAccessibleExam(examId, userId, role)
                .flatMap(exam -> accessPolicy.canManage(userId, role, exam)
                        ? Mono.just(exam)
                        : Mono.<Exam>error(ExamException.notOwner()))
                .flatMap(exam -> stateRepository.load(examId))
                .flatMap(source -> examRepository.save(copyRow(source, userId))
                        .flatMap(copy -> stateRepository.load(copy.getId())
                                .flatMap(created -> structureWriter.apply(copy.getId(), created,
                                        planner.copyOf(source, created.blocks().get(0).id())))
                                .then(Mono.defer(() -> stateRepository.load(copy.getId())))
                                .flatMap(copied -> history.record(copy.getId(), userId, null, change,
                                        List.of(ExamDiff.clonedFrom(source, copied))))
                                .thenReturn(copy)))
                .flatMap(this::loadExamWithQuestions);

        return transactionalOperator.transactional(cloning)
                .flatMap(response -> cacheService.cacheExam(response).thenReturn(response))
                .flatMap(response -> examMedia.withMedia(response, true))
                .flatMap(this::withLastEdits)
                .doOnSuccess(copy -> log.info("Exam {} cloned into {} by {}", examId, copy.getId(), userId));
    }

    private static final String COPY_SUFFIX = " (nüsxə)";

    /**
     * The exam row of a copy: everything of the source's row except id, status (DRAFT), owner and audience groups
     */
    private static Exam copyRow(ExamState source, UUID owner) {
        String title = source.title() == null ? "" : source.title();
        if (title.length() + COPY_SUFFIX.length() > 255) {
            title = title.substring(0, 255 - COPY_SUFFIX.length());
        }
        LocalDateTime now = LocalDateTime.now();
        return Exam.builder()
                .title(title + COPY_SUFFIX)
                .description(source.description())
                .durationMinutes(source.durationMinutes())
                .startTime(source.startTime())
                .endTime(source.endTime())
                .status(ExamStatus.DRAFT)
                .totalQuestions(source.questions().size())
                .passingScore(source.passingScore())
                .shuffleQuestions(source.shuffleQuestions())
                .shuffleOptions(source.shuffleOptions())
                .showResultsImmediately(source.showResultsImmediately())
                .maxAttempts(source.maxAttempts())
                .maxAttemptsPerDay(source.maxAttemptsPerDay())
                .categoryId(source.categoryId())
                .createdBy(owner)
                .accessType(source.accessType())
                .timingMode(source.timingMode())
                .allowReturnToPreviousBlocks(source.allowReturnToPreviousBlocks())
                .allowSkipBreak(source.allowSkipBreak())
                .showBlockBreakdown(source.showBlockBreakdown())
                .active(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
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
                            // The answer key and media preview URLs are only for ADMIN and the INSTRUCTOR who owns the exam,
                            // and so is the audience; the cached object itself is never changed
                            if (accessPolicy.canManage(userId, role, exam)) {
                                Mono<List<Long>> groups = exam.getAccessType() == ExamAccessType.GROUP
                                        ? audienceRepository.findActiveGroupIdsByExamId(examId).collectList()
                                        : Mono.just(List.of());
                                return groups.flatMap(groupIds -> examMedia.withMedia(full.toBuilder()
                                        .accessType(exam.getAccessType())
                                        .audienceGroupCount(groupIds.size())
                                        .groupIds(groupIds)
                                        .build(), true))
                                        .flatMap(this::withLastEdits);
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
                .flatMap(response -> examMedia.withMedia(response, true))
                .flatMap(this::withLastEdits);
    }

    /**
     * One page of the management list: ADMIN every exam, INSTRUCTOR only the own ones, newest first.
     * Archived exams are listed only when status = ARCHIVED is asked for. The search looks for the
     * text anywhere in the title, ignoring case; it needs at least 2 characters.
     */
    public Mono<PageResponse<ManagedExamResponse>> getManagedExams(UUID userId, String role, String search,
                                                                   ExamStatus status, int page, int size) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        String term = search == null ? "" : search.trim();
        if (!term.isEmpty() && (term.length() < MIN_SEARCH_LENGTH || term.length() > MAX_SEARCH_LENGTH)) {
            return Mono.error(new ValidationException("Search text must be between " + MIN_SEARCH_LENGTH
                    + " and " + MAX_SEARCH_LENGTH + " characters"));
        }
        // % and _ typed by the user are searched for as they are, not as wildcards
        String titlePattern = term.isEmpty()
                ? null
                : "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";

        ExamManageRepository.Filter filter = new ExamManageRepository.Filter(
                "ADMIN".equals(role) ? null : userId, status, titlePattern);

        return manageRepository.count(filter)
                .flatMap(total -> manageRepository.findPage(filter, pageSize, (long) pageNumber * pageSize)
                        .collectList()
                        .map(rows -> PageResponse.of(rows, pageNumber, pageSize, total)));
    }

    /**
     * One page of the exams a participant may access and can still start or resume.
     * Which exams are listed is decided in SQL by the access policy; this only adds the attempt figures.
     */
    public Mono<PageResponse<ExamResponse>> getAvailableExams(UUID userId, String role, int page, int size) {
        log.debug("Getting available exams for user {}", userId);

        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        ExamDayClock.Day today = dayClock.today();

        return accessPolicy.countAvailableExams(userId, role)
                .flatMap(total -> accessPolicy.findAvailableExams(userId, role, pageSize, (long) pageNumber * pageSize)
                        .concatMap(exam -> Mono.zip(
                                        attemptRepository.countAttempts(exam.getId(), userId, today.start(), today.end()),
                                        sessionRepository.findActiveSession(exam.getId(), userId)
                                                .map(active -> !active.isExpired())
                                                .defaultIfEmpty(false))
                                .map(tuple -> {
                                    // Same rule as startExam: every session counts as an attempt, in total and
                                    // for today, and an active session that still has time can always be resumed
                                    ExamAttemptRepository.AttemptCounts counts = tuple.getT1();
                                    int remainingInTotal = Math.max(0, maxAttempts(exam) - counts.total());
                                    Integer remainingToday = exam.getMaxAttemptsPerDay() == null
                                            ? null
                                            : Math.max(0, exam.getMaxAttemptsPerDay() - counts.today());
                                    int remaining = remainingToday == null
                                            ? remainingInTotal
                                            : Math.min(remainingInTotal, remainingToday);
                                    boolean resumable = tuple.getT2();
                                    ExamResponse response = mapper.toResponse(exam);
                                    response.setRemainingAttempts(remaining);
                                    response.setRemainingAttemptsToday(remainingToday);
                                    // Only today's limit is in the way, and the exam is still open tomorrow
                                    if (remainingToday != null && remainingToday == 0 && remainingInTotal > 0
                                            && today.end().isBefore(exam.getEndTime())) {
                                        response.setNextAttemptAt(today.end());
                                    }
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

    // The results of an exam (list, summary, review of a session): ExamResultsService

    /**
     * Update exam status
     */
    public Mono<ExamResponse> updateExamStatus(Long examId, ExamStatus newStatus, UUID userId, String role) {
        return updateExamStatus(examId, newStatus, ChangeContext.of(userId, role));
    }

    /**
     * Publish (ACTIVE) or archive; the history gets PUBLISHED or ARCHIVED (sensitive) in the same transaction
     */
    public Mono<ExamResponse> updateExamStatus(Long examId, ExamStatus newStatus, ChangeContext change) {
        UUID userId = change.actorId();
        String role = change.role();
        log.info("Updating exam {} status to {} by user {}", examId, newStatus, userId);

        return findAccessibleExam(examId, userId, role)
                .flatMap(exam -> {
                    // Publishing and archiving: ADMIN every exam, INSTRUCTOR only the own one
                    if (!accessPolicy.canManage(userId, role, exam)) {
                        return Mono.error(ExamException.notOwner());
                    }
                    // Publishing an exam whose window is over would only be undone by the scheduler
                    // within a minute: it needs a new end time first (reschedule)
                    if (newStatus == ExamStatus.ACTIVE && !exam.getEndTime().isAfter(LocalDateTime.now())) {
                        return Mono.error(ExamException.windowEnded());
                    }
                    return Mono.just(exam);
                })
                // B12a: publishing runs the readiness check on the locked exam row, so no edit slips in between
                .flatMap(exam -> transactionalOperator.transactional((newStatus == ExamStatus.ACTIVE
                                        ? editLock.lockForEdit(examId).then(readiness.check(examId)).flatMap(ExamService::requireReady)
                                        : Mono.<Void>empty())
                                .then(examRepository.updateStatus(examId, newStatus.name()))
                                .then(auditForeignExam(userId, exam, statusAuditAction(newStatus)))
                                .then(statusHistory(exam, newStatus, change)))
                        .then(examRepository.findById(examId)))
                .flatMap(this::loadExamWithQuestions)
                .flatMap(response -> cacheService.invalidateExamCache(examId)
                        .then(cacheService.cacheExam(response))
                        .thenReturn(response))
                .flatMap(response -> examMedia.withMedia(response, true))
                .flatMap(this::withLastEdits)
                .doOnSuccess(exam -> log.info("Exam {} status updated to {}", examId, newStatus));
    }

    /**
     * Open a published exam again with a new time window: SCHEDULED when the start is still ahead,
     * otherwise ACTIVE. Works on SCHEDULED, ACTIVE, COMPLETED and ARCHIVED exams (so it also brings
     * an archived exam back); a draft is changed with the normal update instead.
     * Only the exam row changes: questions, sessions and results stay as they are, and a session in
     * progress keeps its own end time. More attempts take effect at once; fewer never remove a session.
     * A COMPLETED or ARCHIVED exam with attempts is never made SCHEDULED (409 EXAM_HAS_SESSIONS): it is
     * reopened with a start time now or in the past (ACTIVE).
     */
    public Mono<ExamResponse> rescheduleExam(Long examId, RescheduleExamRequest request, UUID userId, String role) {
        return rescheduleExam(examId, request, ChangeContext.of(userId, role));
    }

    /**
     * Reschedule; the history gets RESCHEDULED (sensitive when the window changes) in the same transaction
     */
    public Mono<ExamResponse> rescheduleExam(Long examId, RescheduleExamRequest request, ChangeContext change) {
        UUID userId = change.actorId();
        String role = change.role();
        log.info("Rescheduling exam {} by user {}", examId, userId);

        Mono<Exam> rescheduling = findAccessibleExam(examId, userId, role)
                .flatMap(exam -> accessPolicy.canManage(userId, role, exam)
                        ? Mono.just(exam)
                        : Mono.<Exam>error(ExamException.notOwner()))
                // Decided on the locked row, so two reschedules of the same exam take turns
                .flatMap(checked -> examRepository.lockById(examId))
                .switchIfEmpty(Mono.error(ExamException.notFound(examId)))
                .flatMap(exam -> {
                    if (!RESCHEDULABLE.contains(exam.getStatus())) {
                        return Mono.error(ExamException.cannotReschedule());
                    }
                    LocalDateTime now = LocalDateTime.now();
                    LocalDateTime startTime = request.getStartTime() != null ? request.getStartTime() : exam.getStartTime();
                    LocalDateTime endTime = request.getEndTime();
                    if (!endTime.isAfter(now)) {
                        return Mono.error(ExamException.endTimeInPast());
                    }
                    if (!endTime.isAfter(startTime)) {
                        return Mono.error(ExamException.invalidTimeRange());
                    }
                    ExamStatus newStatus = startTime.isAfter(now) ? ExamStatus.SCHEDULED : ExamStatus.ACTIVE;
                    Integer newMaxAttempts = request.getMaxAttempts() != null ? request.getMaxAttempts() : exam.getMaxAttempts();
                    Integer newMaxAttemptsPerDay = request.isMaxAttemptsPerDayPresent()
                            ? request.getMaxAttemptsPerDay()
                            : exam.getMaxAttemptsPerDay();

                    // A finished or archived exam with attempts must not become SCHEDULED: that status would open
                    // it for editing again. Reopening it at once (ACTIVE) stays possible. Asked after the row lock.
                    boolean backToScheduled = newStatus == ExamStatus.SCHEDULED
                            && (exam.getStatus() == ExamStatus.COMPLETED || exam.getStatus() == ExamStatus.ARCHIVED);
                    Mono<Void> attemptsGuard = backToScheduled
                            ? editLock.hasSessions(examId).flatMap(taken -> taken
                                    ? Mono.<Void>error(ExamException.rescheduleHasSessions())
                                    : Mono.<Void>empty())
                            : Mono.empty();

                    // Reschedule always leaves the exam SCHEDULED or ACTIVE, i.e. published (B12a gate)
                    return attemptsGuard
                            .then(Mono.defer(() -> stateRepository.load(examId).flatMap(this::requireBlockRulesAvailable)))
                            .then(Mono.defer(() -> examRepository.reschedule(examId, startTime, endTime, newStatus.name(),
                                    request.getMaxAttempts(), request.isMaxAttemptsPerDayPresent(),
                                    request.getMaxAttemptsPerDay())
                            .then(auditService.record(userId, GroupAuditService.EXAM_RESCHEDULED, null, exam.getCreatedBy(),
                                    "examId=" + examId
                                            + " status=" + exam.getStatus() + " -> " + newStatus
                                            + " window=" + UtcDateTimeModule.format(exam.getStartTime())
                                            + ".." + UtcDateTimeModule.format(exam.getEndTime())
                                            + " -> " + UtcDateTimeModule.format(startTime)
                                            + ".." + UtcDateTimeModule.format(endTime)
                                            + " maxAttempts=" + exam.getMaxAttempts() + " -> " + newMaxAttempts
                                            + " maxAttemptsPerDay=" + exam.getMaxAttemptsPerDay()
                                            + " -> " + newMaxAttemptsPerDay))
                            .then(history.record(examId, exam.getCreatedBy(), exam.getStatus(), change,
                                    List.of(ExamDiff.rescheduled(examId, exam.getStatus(), newStatus, exam.getStartTime(),
                                            startTime, exam.getEndTime(), endTime, exam.getMaxAttempts(), newMaxAttempts,
                                            exam.getMaxAttemptsPerDay(), newMaxAttemptsPerDay))))
                            .then(examRepository.findById(examId))));
                });

        // The exam row and its audit entry change together; the cached exam is dropped after commit.
        // The answer is the exam itself, without questions (so without the answer key).
        return transactionalOperator.transactional(rescheduling)
                .flatMap(exam -> cacheService.invalidateExamCache(examId).thenReturn(mapper.toResponse(exam)))
                .doOnSuccess(exam -> log.info("Exam {} rescheduled: {} until {}", examId, exam.getStatus(), exam.getEndTime()));
    }

    /**
     * Set who may take an exam: PUBLIC (everyone) or GROUP (members of the given groups).
     * Allowed for the owner and ADMIN in every exam status; changing a published exam takes effect
     * at once for new starts, while sessions already in progress can be finished.
     * This only stores the audience; who has access is decided by ExamAccessPolicy.
     */
    public Mono<ExamAudienceResponse> setAudience(Long examId, ExamAudienceRequest request, UUID userId, String role) {
        return setAudience(examId, request, ChangeContext.of(userId, role));
    }

    /**
     * Set the audience; the history gets AUDIENCE_CHANGED (when something changed) in the same transaction
     */
    public Mono<ExamAudienceResponse> setAudience(Long examId, ExamAudienceRequest request, ChangeContext change) {
        UUID userId = change.actorId();
        String role = change.role();
        ExamAccessType accessType = request.getAccessType();
        List<Long> groupIds = audienceGroupIds(accessType, request.getGroupIds());

        Mono<ExamAudienceResponse> audienceChange = findAccessibleExam(examId, userId, role)
                .flatMap(exam -> {
                    if (!"ADMIN".equals(role) && !exam.getCreatedBy().equals(userId)) {
                        return Mono.error(ExamException.notOwner());
                    }
                    return audienceRepository.findGroupIdsByExamId(examId).collectList()
                            .flatMap(stored -> requireValidAudience(accessType, groupIds, userId, role, stored))
                            .thenReturn(exam);
                })
                .flatMap(exam -> audienceRepository.findGroupIdsByExamId(examId).collectList()
                        .flatMap(oldGroupIds -> examRepository.updateAccessType(examId, accessType.name())
                                .then(writeAudience(examId, exam.getStatus(), exam.getAccessType(), accessType,
                                        oldGroupIds, groupIds, userId))
                                .then(history.record(examId, exam.getCreatedBy(), exam.getStatus(), change,
                                        ExamDiff.audience(examId, exam.getAccessType(), accessType, oldGroupIds, groupIds)))
                                .thenReturn(ExamAudienceResponse.builder()
                                        .examId(examId)
                                        .accessType(accessType)
                                        .groupIds(groupIds)
                                        .build())));

        // Audience rows, access type and audit entry change together; the cached exam is dropped after commit
        return transactionalOperator.transactional(audienceChange)
                .flatMap(response -> cacheService.invalidateExamCache(examId).thenReturn(response))
                .doOnSuccess(response -> log.info("Exam {} audience set to {} {} by user {}",
                        examId, response.getAccessType(), response.getGroupIds(), userId));
    }

    /**
     * How many participants an audience would reach: the members of each group and how many
     * different people that is. Without groupIds the exam's stored audience is used.
     * For ADMIN and the INSTRUCTOR who owns the exam; nothing is stored.
     */
    public Mono<ExamAudiencePreviewResponse> previewAudience(Long examId, List<Long> groupIds, UUID userId, String role) {
        List<Long> requested = groupIds == null ? List.of() : groupIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (requested.size() > MAX_AUDIENCE_GROUPS) {
            return Mono.error(new ValidationException("At most " + MAX_AUDIENCE_GROUPS + " groups can be previewed"));
        }

        // The stored audience is always shown; other groups only when the caller may use them
        return accessPolicy.findManageableExam(examId, userId, role)
                .flatMap(exam -> audienceRepository.findGroupIdsByExamId(examId).collectList()
                        .flatMap(stored -> requested.isEmpty()
                                ? countAudience(stored, Set.copyOf(stored))
                                : usableGroupIds(requested, userId, role, stored)
                                        .flatMap(usable -> countAudience(requested, usable)))
                        .map(counted -> {
                            counted.setExamId(examId);
                            counted.setAccessType(exam.getAccessType());
                            counted.setStoredAudience(requested.isEmpty());
                            return counted;
                        }));
    }

    /**
     * The same counts for groups that are not stored anywhere yet (an exam being created): the
     * members of each group and how many different people that is. Nothing is written. For everyone
     * who may set an audience (ADMIN, INSTRUCTOR); the controller checks the role. For an INSTRUCTOR
     * the groups of other owners are listed as unknown (GroupAccessPolicy).
     */
    public Mono<ExamAudiencePreviewResponse> previewGroups(List<Long> groupIds, UUID userId, String role) {
        List<Long> requested = groupIds == null ? List.of() : groupIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (requested.size() > MAX_AUDIENCE_GROUPS) {
            return Mono.error(new ValidationException("At most " + MAX_AUDIENCE_GROUPS + " groups can be previewed"));
        }
        return usableGroupIds(requested, userId, role, List.of())
                .flatMap(usable -> countAudience(requested, usable));
    }

    /**
     * Members per group and the distinct total of the given groups: two statements however many
     * groups there are. Only the usable ones are counted; the others (unknown, deleted, or another
     * owner's group for an INSTRUCTOR) are listed as unknown, so they are not told apart.
     */
    private Mono<ExamAudiencePreviewResponse> countAudience(List<Long> requested, Set<Long> usable) {
        List<Long> ids = requested.stream().filter(usable::contains).toList();
        if (ids.isEmpty()) {
            return Mono.just(ExamAudiencePreviewResponse.builder()
                    .perGroup(List.of())
                    .distinctEligible(0)
                    .unknownGroupIds(requested)
                    .build());
        }
        return Mono.zip(membershipRepository.countMembersPerGroup(ids).collectList(),
                        membershipRepository.countDistinctMembers(ids))
                .map(loaded -> {
                    List<ExamAudiencePreviewResponse.GroupMembers> perGroup = loaded.getT1().stream()
                            .map(group -> new ExamAudiencePreviewResponse.GroupMembers(
                                    group.groupId(), group.name(), group.members()))
                            .toList();
                    Set<Long> known = perGroup.stream()
                            .map(ExamAudiencePreviewResponse.GroupMembers::getGroupId)
                            .collect(Collectors.toSet());
                    return ExamAudiencePreviewResponse.builder()
                            .perGroup(perGroup)
                            .distinctEligible(loaded.getT2())
                            .unknownGroupIds(requested.stream().filter(id -> !known.contains(id)).toList())
                            .build();
                });
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
     * A GROUP audience needs at least one group, and every group must be usable by the caller:
     * active, and for an INSTRUCTOR an own group or one the exam already has (an audience an ADMIN
     * set stays editable). Anything else is AUDIENCE_GROUP_NOT_FOUND, whatever the reason.
     */
    private Mono<Void> requireValidAudience(ExamAccessType accessType, List<Long> groupIds, UUID userId, String role,
                                            List<Long> storedGroupIds) {
        if (accessType == ExamAccessType.GROUP && groupIds.isEmpty()) {
            return Mono.error(GroupException.audienceGroupsRequired());
        }
        if (groupIds.isEmpty()) {
            return Mono.empty();
        }
        return usableGroupIds(groupIds, userId, role, storedGroupIds)
                .flatMap(usable -> usable.containsAll(groupIds)
                        ? Mono.<Void>empty()
                        : Mono.<Void>error(GroupException.audienceGroupNotFound()));
    }

    /**
     * Those of the ids the caller may put into an audience: active groups, for an INSTRUCTOR only the
     * own ones plus those already stored on the exam (GroupAccessPolicy). One or two statements.
     */
    private Mono<Set<Long>> usableGroupIds(List<Long> ids, UUID userId, String role, List<Long> storedGroupIds) {
        if (ids.isEmpty()) {
            return Mono.just(Set.of());
        }
        UUID owner = GroupAccessPolicy.ownerFilter(userId, role);
        Mono<Set<Long>> manageable = groupRepository.findManageableIds(ids, owner).collect(Collectors.toSet());
        List<Long> stored = ids.stream().filter(storedGroupIds::contains).toList();
        if (owner == null || stored.isEmpty()) {
            return manageable;
        }
        Mono<Set<Long>> storedActive = groupRepository.findManageableIds(stored, null).collect(Collectors.toSet());
        return Mono.zip(manageable, storedActive).map(both -> {
            Set<Long> usable = new HashSet<>(both.getT1());
            usable.addAll(both.getT2());
            return usable;
        });
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

    /**
     * Audit entry for a change to an exam by someone who did not create it; only an ADMIN gets that
     * far (canManage). The owner changing the own exam leaves no entry. Part of the caller's transaction.
     */
    private Mono<Void> auditForeignExam(UUID actorId, Exam exam, String action) {
        if (actorId.equals(exam.getCreatedBy())) {
            return Mono.empty();
        }
        log.info("Admin {} on exam {} of user {}: {}", actorId, exam.getId(), exam.getCreatedBy(), action);
        return auditService.record(actorId, action, null, exam.getCreatedBy(),
                "examId=" + exam.getId() + " status=" + exam.getStatus());
    }

    /**
     * Audit entry for a deleted exam, written for the owner too: the row is gone afterwards, so
     * this is the only trace of it. Part of the caller's transaction.
     */
    private Mono<Void> auditDeletedExam(UUID actorId, Exam exam) {
        String title = exam.getTitle() == null ? "" : exam.getTitle();
        return auditService.record(actorId,
                actorId.equals(exam.getCreatedBy()) ? GroupAuditService.EXAM_DELETED : GroupAuditService.EXAM_DELETED_BY_ADMIN,
                null, exam.getCreatedBy(),
                "examId=" + exam.getId() + " status=" + exam.getStatus()
                        + " title=" + (title.length() > 100 ? title.substring(0, 100) : title));
    }

    /**
     * The history row of a publish or archive; part of the caller's transaction. A publish also stores the
     * snapshot of the exam (ExamSnapshotService) and names its version in the row.
     */
    private Mono<Void> statusHistory(Exam exam, ExamStatus newStatus, ChangeContext change) {
        if (newStatus == ExamStatus.ACTIVE) {
            return snapshots.publish(exam.getId(), change)
                    .flatMap(version -> history.record(exam.getId(), exam.getCreatedBy(), exam.getStatus(), change,
                            List.of(ExamDiff.status("PUBLISHED", exam.getStatus(), newStatus, exam.getId(), version))));
        }
        return history.record(exam.getId(), exam.getCreatedBy(), exam.getStatus(), change,
                List.of(ExamDiff.status("ARCHIVED", exam.getStatus(), newStatus, exam.getId(), null)));
    }

    private String statusAuditAction(ExamStatus newStatus) {
        return switch (newStatus) {
            case ACTIVE -> GroupAuditService.EXAM_PUBLISHED_BY_ADMIN;
            case ARCHIVED -> GroupAuditService.EXAM_ARCHIVED_BY_ADMIN;
            default -> GroupAuditService.EXAM_STATUS_CHANGED_BY_ADMIN;
        };
    }

    private int maxAttempts(Exam exam) {
        return exam.getMaxAttempts() != null ? exam.getMaxAttempts() : 1;
    }

    /**
     * B12a, for ADMIN and the owner: who changed each question last and when. A copy: the given response may be the
     * cached instance, which never carries it (participants never get it).
     */
    private Mono<ExamResponse> withLastEdits(ExamResponse exam) {
        if (exam.getQuestions() == null || exam.getQuestions().isEmpty()) {
            return Mono.just(exam);
        }
        return history.lastQuestionEdits(exam.getId())
                .map(edits -> edits.isEmpty() ? exam : exam.toBuilder()
                        .questions(exam.getQuestions().stream()
                                .map(question -> {
                                    var edit = edits.get(question.getId());
                                    return edit == null ? question : question.toBuilder()
                                            .lastEditedBy(ExamHistoryDtos.Actor.builder().userId(edit.actorId())
                                                    .name(edit.actorName()).code(edit.actorCode()).build())
                                            .lastEditedAt(edit.at())
                                            .build();
                                })
                                .toList())
                        .build());
    }

    /**
     * The timing mode an update leaves the exam with: the request's, or the stored one
     */
    private static TimingMode timingModeAfter(Exam exam, UpdateExamRequest request) {
        if (request.getTimingMode() != null) {
            return request.getTimingMode();
        }
        return exam.getTimingMode() != null ? exam.getTimingMode() : TimingMode.EXAM_TIMER;
    }

    /**
     * Every question of a request, from its blocks or from the older flat list
     */
    private static List<QuestionDTO> requestQuestions(List<ExamBlockDTO> blocks, List<QuestionDTO> questions) {
        if (blocks != null && !blocks.isEmpty()) {
            return blocks.stream()
                    .flatMap(block -> block.getQuestions() == null ? java.util.stream.Stream.<QuestionDTO>empty()
                            : block.getQuestions().stream())
                    .toList();
        }
        return questions != null ? questions : List.of();
    }

    /**
     * B12a: what the readiness check blocks stops a publication. Block rules before B12b are 409
     * FEATURE_NOT_AVAILABLE, anything else 409 EXAM_NOT_READY; details carry the list.
     */
    static Mono<Void> requireReady(ExamReadinessResponse readiness) {
        if (readiness.isReady()) {
            return Mono.empty();
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("blocking", readiness.getBlocking());
        details.put("warnings", readiness.getWarnings());
        boolean onlyBlockRules = readiness.getBlocking().stream().anyMatch(item -> "FEATURE_NOT_AVAILABLE".equals(item.getCode()));
        return Mono.error(onlyBlockRules ? ExamException.featureNotAvailable(details) : ExamException.notReady(details));
    }

    /**
     * B12a, until B12b: participants cannot be given block timers, fixed navigation or a return to earlier blocks
     * yet, so an exam that would need them is not published (409 FEATURE_NOT_AVAILABLE) unless
     * exam.blocks.rules-enabled is on
     */
    Mono<Void> requireBlockRulesAvailable(ExamState state) {
        if (features.blockRulesEnabled() || !state.needsBlockRules()) {
            return Mono.empty();
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("timingMode", state.timingMode());
        details.put("allowReturnToPreviousBlocks", state.allowReturnToPreviousBlocks());
        details.put("blocksWithoutFreeNavigation", state.blocks().stream()
                .filter(block -> !block.freeNavigation()).map(ExamState.BlockState::id).toList());
        return Mono.error(ExamException.featureNotAvailable(details));
    }

    /**
     * The exam row of an update, and its audience when the request names one. Part of the caller's transaction.
     */
    private Mono<Exam> saveExamRow(Exam exam, UpdateExamRequest request, ExamAccessType newAccessType,
                                   List<Long> groupIds, UUID userId, Integer duration) {
        Long examId = exam.getId();
        ExamAccessType oldAccessType = exam.getAccessType();
        mapper.updateEntity(exam, request);
        // With block timers: the block limits plus the breaks (ExamValidator.validateTiming)
        exam.setDurationMinutes(duration);
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
                })
                // B12a: the blocks, each with its questions' ids
                .flatMap(response -> blockRepository.findByExamIdOrderByPosition(exam.getId()).collectList()
                        .map(blocks -> {
                            response.setBlocks(mapper.toBlockResponses(blocks, response.getQuestions()));
                            return response;
                        }));
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

        ExamDayClock.Day today = dayClock.dayOf(now);

        // The total limit, the daily limit and the single active session are all decided by this one
        // statement; its short transaction only carries the lock that makes parallel starts take turns.
        // Whatever it did, the active session (ours, or the one a concurrent start created) is the answer;
        // none at all means a limit refused the insert.
        return transactionalOperator.transactional(attemptRepository.openSessionIfAttemptsRemain(sessionId,
                        exam.getId(), userId, now, endTime, request.getIpAddress(), request.getBrowserInfo(),
                        today.start(), today.end()))
                .flatMap(inserted -> sessionRepository.findActiveSession(exam.getId(), userId))
                .switchIfEmpty(Mono.defer(() -> refusedStart(exam, userId, today)));
    }

    /**
     * Why a start was refused: the total limit when it is used up, otherwise today's limit
     */
    private Mono<ExamSession> refusedStart(Exam exam, UUID userId, ExamDayClock.Day today) {
        return attemptRepository.countAttempts(exam.getId(), userId, today.start(), today.end())
                .flatMap(counts -> Mono.error(counts.total() >= maxAttempts(exam)
                        ? ExamException.maxAttemptsReached()
                        : ExamException.dailyAttemptsReached(today.end())));
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
