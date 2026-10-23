package com.examportal.exam.controller;

import com.examportal.common.domain.dto.*;
import com.examportal.common.domain.dto.group.AudiencePreviewRequest;
import com.examportal.common.domain.dto.group.ExamAudiencePreviewResponse;
import com.examportal.common.domain.dto.group.ExamAudienceRequest;
import com.examportal.common.domain.dto.group.ExamAudienceResponse;
import com.examportal.common.domain.dto.media.MediaUrlResponse;
import com.examportal.common.domain.entity.ExamStatus;
import com.examportal.common.domain.entity.GradingStatus;
import com.examportal.common.domain.entity.RiskLevel;
import com.examportal.common.domain.entity.SessionStatus;
import com.examportal.common.security.UserPrincipal;
import com.examportal.exam.history.ChangeContextFactory;
import com.examportal.exam.service.AnswerSubmissionService;
import com.examportal.exam.service.InstructorLimitsService;
import com.examportal.exam.service.ExamAccessPolicy;
import com.examportal.exam.service.ExamReadinessService;
import com.examportal.exam.service.ExamMetaService;
import com.examportal.exam.service.ExamResultsService;
import com.examportal.exam.service.ExamService;
import com.examportal.exam.service.FraudEventService;
import com.examportal.exam.service.SessionMediaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Exam Controller
 * REST endpoints for exam management.
 */
@Slf4j
@RestController
@RequestMapping("/api/exams")
@RequiredArgsConstructor
@Tag(name = "Exam Management", description = "Endpoints for managing exams")
@SecurityRequirement(name = "bearerAuth")
public class ExamController {

    private final ExamService examService;
    private final InstructorLimitsService limitsService;
    private final ExamMetaService examMetaService;
    private final AnswerSubmissionService answerService;
    private final SessionMediaService sessionMediaService;
    private final FraudEventService fraudEventService;
    private final ExamResultsService resultsService;
    // B12a: who changes an exam, from where and why (the history)
    private final ChangeContextFactory changes;
    private final ExamReadinessService readinessService;
    private final ExamAccessPolicy accessPolicy;

    // ==================== Exam CRUD Operations ====================

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Create a new exam", description = "Create a new exam with questions and options. B13: accessType PUBLIC is for ADMIN only (403 PUBLIC_EXAM_ADMIN_ONLY); without accessType ADMIN's exam is PUBLIC and a teacher's GROUP (the groups given, or none yet: readiness AUDIENCE_EMPTY). Questions may carry media (up to 3: {mediaId, position, maxPlays for audio/video}) and options one image (mediaIds); only your own confirmed media. WARNING: the alt text of option media is shown to participants and must not reveal the answer. "
            + "B12a: either questions (one block 'Əsas blok') or blocks (1..20, in order, each {title, instructions, timeLimitMinutes, breakAfterMinutes, freeNavigation, shuffleQuestions, showResultToParticipant, questions}); "
            + "timingMode EXAM_TIMER (default) or BLOCK_TIMERS (every block needs timeLimitMinutes; the duration becomes the limits plus the breaks, 400 EXAM_DURATION_EXCEEDS_WINDOW when it does not fit start..end), "
            + "allowReturnToPreviousBlocks, allowSkipBreak, showBlockBreakdown. The answer carries blocks and totalMinutes.")
    public Mono<ApiResponse<ExamResponse>> createExam(
            @Valid @RequestBody CreateExamRequest request,
            ServerHttpRequest httpRequest,
            @AuthenticationPrincipal UserPrincipal principal) {

        log.info("Creating exam: {} by user: {}", request.getTitle(), principal.getId());

        // B13: a teacher's limit of exams (ADMIN has none)
        return limitsService.requireRoom(principal.getId(), principal.getRole(), InstructorLimitsService.EXAMS, 1)
                .then(Mono.defer(() -> changes.create(principal, httpRequest)))
                .flatMap(change -> examService.createExam(request, change))
                .map(exam -> ApiResponse.success(exam, "Exam created successfully"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Update an exam", description = "Update an existing exam (DRAFT or SCHEDULED only, never with attempts: 409 EXAM_HAS_SESSIONS). Questions may carry media (up to 3: {mediaId, position, maxPlays for audio/video}) and options one image (mediaIds); your own confirmed media, or media the exam already carries (ADMIN: any). WARNING: the alt text of option media is shown to participants and must not reveal the answer. "
            + "B12a: changed IN PLACE: a block, question or option with its id keeps its row and id (a question may move to another block), one without an id is new, one left out is deleted. "
            + "Either questions (the exam's only block; 400 BLOCKS_REQUIRED for an exam with more) or blocks, as on create. Fields left out (timingMode, allowReturnToPreviousBlocks, allowSkipBreak, showBlockBreakdown) stay. "
            + "A SCHEDULED exam cannot get block timers, fixed navigation or returning to earlier blocks before B12b (409 FEATURE_NOT_AVAILABLE). "
            + "Every change is written to the history (GET /{id}/history) with one saveId; changeReason (body or query, 3-500 characters) is optional, "
            + "and required (400 CHANGE_REASON_REQUIRED) for a sensitive change of a SCHEDULED or ACTIVE exam only when REQUIRE_CHANGE_REASON_AFTER_PUBLISH is on.")
    public Mono<ApiResponse<ExamResponse>> updateExam(
            @PathVariable Long id,
            @Valid @RequestBody UpdateExamRequest request,
            @RequestParam(required = false) @Parameter(description = "Why (optional, 3-500 characters); the body field wins") String changeReason,
            ServerHttpRequest httpRequest,
            @AuthenticationPrincipal UserPrincipal principal) {

        log.info("Updating exam: {} by user: {}", id, principal.getId());

        return changes.create(principal, httpRequest, request.getChangeReason(), changeReason)
                .flatMap(change -> examService.updateExam(id, request, change))
                .map(exam -> ApiResponse.success(exam, "Exam updated successfully"));
    }

    @GetMapping("/{id}/readiness")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Can the exam be published?", description = "B12a: blocking (code, messageKey, blockId/questionId "
            + "with their numbers): NO_QUESTIONS, EMPTY_BLOCK, MULTIPLE_CHOICE_INCOMPLETE, NO_CORRECT_ANSWER, TRUE_FALSE_INCOMPLETE, "
            + "FILL_BLANK_INCOMPLETE, SHORT_ANSWER_INCOMPLETE, ORDERING_INCOMPLETE, MATCHING_INCOMPLETE, LINEAR_SCALE_INCOMPLETE, ZERO_POINTS, MEDIA_MISSING, BLOCK_TIME_LIMIT_MISSING, DURATION_EXCEEDS_WINDOW, WINDOW_ENDED, "
            + "FEATURE_NOT_AVAILABLE (block rules before B12b); warnings: AUDIENCE_EMPTY, START_IN_PAST, BLOCK_TIME_LIMIT_IGNORED. "
            + "Publishing runs the same check (409 EXAM_NOT_READY / FEATURE_NOT_AVAILABLE with the list). ADMIN or the owning INSTRUCTOR.")
    public Mono<ApiResponse<ExamReadinessResponse>> readiness(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return readinessService.check(id, principal.getId(), principal.getRole(), accessPolicy)
                .map(ApiResponse::success);
    }

    @PostMapping("/{id}/clone")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Copy an exam", description = "B12a: a new DRAFT owned by the caller with the blocks, questions, options, "
            + "answer key, settings and media (the same media files; a media used by two exams cannot be deleted, 409 MEDIA_IN_USE), "
            + "titled '<title> (nüsxə)'. Not copied: audience groups (a GROUP copy starts with none), sessions, results, history. "
            + "The copy's history starts with CLONED_FROM. ADMIN or the owning INSTRUCTOR (404 EXAM_NOT_FOUND otherwise, as for an exam that does not exist).")
    public Mono<ApiResponse<ExamResponse>> cloneExam(
            @PathVariable Long id,
            ServerHttpRequest httpRequest,
            @AuthenticationPrincipal UserPrincipal principal) {
        // B13: a copy is a new exam of the teacher
        return limitsService.requireRoom(principal.getId(), principal.getRole(), InstructorLimitsService.EXAMS, 1)
                .then(Mono.defer(() -> changes.create(principal, httpRequest)))
                .flatMap(change -> examService.cloneExam(id, change))
                .map(exam -> ApiResponse.success(exam, "Exam copied"));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Delete an exam", description = "Removes an exam for good, with its questions, options, media links "
            + "and audience. Only an exam without any session: otherwise 409 EXAM_CANNOT_DELETE and nothing is removed; archive it instead. "
            + "B12a: the history of the exam stays and records the attempt either way (DELETE_ATTEMPTED, with the whole exam when it was deleted).")
    public Mono<Void> deleteExam(
            @PathVariable Long id,
            @RequestParam(required = false) @Parameter(description = "Why (optional, 3-500 characters)") String changeReason,
            ServerHttpRequest httpRequest,
            @AuthenticationPrincipal UserPrincipal principal) {

        log.info("Deleting exam: {} by user: {}", id, principal.getId());

        return changes.create(principal, httpRequest, changeReason)
                .flatMap(change -> examService.deleteExam(id, change));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get exam by ID", description = "Get exam details by ID")
    public Mono<ApiResponse<ExamResponse>> getExam(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        
        log.debug("Getting exam: {} for user: {}", id, principal.getId());
        
        return examService.getExamById(id, principal.getId(), principal.getRole())
                .map(ApiResponse::success);
    }

    @GetMapping("/my")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Get my exams", description = "Get exams created by the current user")
    public Flux<ExamResponse> getMyExams(
            @RequestParam(required = false) ExamStatus status,
            @AuthenticationPrincipal UserPrincipal principal) {
        
        log.debug("Getting exams for user: {} with status: {}", principal.getId(), status);
        
        return examService.getMyExams(principal.getId(), status);
    }

    @GetMapping("/manage")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Get the management list", description = "Paginated exams without their questions, newest first: "
            + "ADMIN every exam, INSTRUCTOR only the own ones. Each row carries sessionCount and participantCount. "
            + "Archived exams are listed only with status=ARCHIVED. search looks in the title (at least 2 characters).")
    public Mono<ApiResponse<PageResponse<ManagedExamResponse>>> getManagedExams(
            @RequestParam(required = false) @Parameter(description = "Text in the title, ignoring case (min 2 characters)") String search,
            @RequestParam(required = false) @Parameter(description = "Only exams in this status") ExamStatus status,
            @RequestParam(defaultValue = "0") @Parameter(description = "Page number (0-indexed)") int page,
            @RequestParam(defaultValue = "20") @Parameter(description = "Page size (max 50)") int size,
            @AuthenticationPrincipal UserPrincipal principal) {

        return examService.getManagedExams(principal.getId(), principal.getRole(), search, status, page, size)
                .map(ApiResponse::success);
    }

    @GetMapping("/available")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR', 'STUDENT')")
    @Operation(summary = "Get available exams", description = "Paginated exams the user may access and can still start")
    public Mono<ApiResponse<PageResponse<ExamResponse>>> getAvailableExams(
            @RequestParam(defaultValue = "0") @Parameter(description = "Page number (0-indexed)") int page,
            @RequestParam(defaultValue = "25") @Parameter(description = "Page size (max 50)") int size,
            @AuthenticationPrincipal UserPrincipal principal) {
        
        log.debug("Getting available exams for user: {}", principal.getId());
        
        return examService.getAvailableExams(principal.getId(), principal.getRole(), page, size)
                .map(ApiResponse::success);
    }

    @GetMapping("/my-sessions")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get my sessions", description = "Paginated sessions of the current user, newest first. "
            + "Score fields are present only when resultsAvailable is true (same rule as the result endpoint).")
    public Mono<ApiResponse<PageResponse<MySessionResponse>>> getMySessions(
            @RequestParam(defaultValue = "0") @Parameter(description = "Page number (0-indexed)") int page,
            @RequestParam(defaultValue = "20") @Parameter(description = "Page size (max 50)") int size,
            @AuthenticationPrincipal UserPrincipal principal) {

        return answerService.getMySessions(principal.getId(), page, size)
                .map(ApiResponse::success);
    }

    // ==================== Exam Session Operations ====================

    @PostMapping("/{id}/start")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR', 'STUDENT')")
    @Operation(summary = "Start an exam", description = "Start an exam session")
    public Mono<ApiResponse<StartExamResponse>> startExam(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) StartExamRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        
        log.info("Starting exam: {} for user: {}", id, principal.getId());
        
        StartExamRequest startRequest = request != null ? request : new StartExamRequest();
        
        return examService.startExam(id, principal.getId(), principal.getRole(), startRequest)
                .map(response -> ApiResponse.success(response, "Exam started successfully"));
    }

    @GetMapping("/session/{sessionId}/questions")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get next questions", description = "Get the next batch of questions for a session")
    public Mono<ApiResponse<List<QuestionResponse>>> getNextQuestions(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "0") @Parameter(description = "Batch number (0-indexed)") int batch,
            @AuthenticationPrincipal UserPrincipal principal) {

        log.debug("Getting questions batch {} for session: {}", batch, sessionId);

        return examService.getNextQuestions(sessionId, batch, principal.getId())
                .map(ApiResponse::success);
    }

    @GetMapping("/session/{sessionId}/media/{mediaId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR', 'STUDENT')")
    @Operation(summary = "Get a media URL", description = "Short-lived (5 minutes) download URL for one media of the session's exam. "
            + "Only inside the caller's own running session. Audio/video with maxPlays: every call counts as one play, "
            + "409 MEDIA_PLAYS_EXHAUSTED when used up; images are unlimited.")
    public Mono<ApiResponse<MediaUrlResponse>> getSessionMediaUrl(
            @PathVariable String sessionId,
            @PathVariable UUID mediaId,
            @AuthenticationPrincipal UserPrincipal principal) {

        return sessionMediaService.getMediaUrl(sessionId, mediaId, principal.getId())
                .map(ApiResponse::success);
    }

    @GetMapping("/session/{sessionId}/answers")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get saved answers", description = "The answers stored in the caller's own session (any status), "
            + "as they were given: questionId, selectedOptionIds, textAnswer, answeredAt. Never the answer key or a score.")
    public Mono<ApiResponse<List<SavedAnswerResponse>>> getSavedAnswers(
            @PathVariable String sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {

        return answerService.getSavedAnswers(sessionId, principal.getId())
                .map(ApiResponse::success);
    }

    @PostMapping("/session/{sessionId}/fraud-event")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Report a fraud event", description = "Records something the participant's browser noticed "
            + "(TAB_SWITCH, WINDOW_BLUR, FULLSCREEN_EXIT, COPY_ATTEMPT, PASTE_ATTEMPT, DEVTOOLS, OTHER) in the caller's "
            + "own running session. Always 202; nothing is decided from it automatically. 409 SESSION_COMPLETED after the session ended. "
            + "Only the first 300 events of a session are stored.")
    public Mono<ApiResponse<Void>> reportFraudEvent(
            @PathVariable String sessionId,
            @Valid @RequestBody FraudEventRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        return fraudEventService.record(sessionId, request, principal.getId())
                .then(Mono.fromSupplier(() -> ApiResponse.<Void>success(null, "Event received")));
    }

    @PostMapping("/session/{sessionId}/answer")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Submit an answer", description = "Submit an answer for a question")
    public Mono<ApiResponse<SubmitAnswerResponse>> submitAnswer(
            @PathVariable String sessionId,
            @Valid @RequestBody SubmitAnswerRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        
        log.debug("Submitting answer for session: {} question: {}", sessionId, request.getQuestionId());
        
        return answerService.submitAnswer(sessionId, request, principal.getId())
                .map(ApiResponse::success);
    }

    @PostMapping("/session/{sessionId}/submit")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Submit exam", description = "Submit the exam and get results")
    public Mono<ApiResponse<SessionResultResponse>> submitExam(
            @PathVariable String sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        
        log.info("Submitting exam for session: {}", sessionId);
        
        return answerService.submitExam(sessionId, principal.getId())
                .map(result -> ApiResponse.success(result, "Exam submitted successfully"));
    }

    // ==================== B12b: the blocks of a session ====================

    @GetMapping("/session/{sessionId}/blocks")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Where the session is among the blocks", description = "Exams with block rules only (409 NO_BLOCK_RULES "
            + "otherwise): the phase (BLOCK, BREAK, DONE), the blocks with their state, the deadline of the current block or "
            + "the end of the break, and the questions that may be seen now. A session whose last block is over is completed.")
    public Mono<ApiResponse<SessionBlocksResponse>> getSessionBlocks(
            @PathVariable String sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return examService.sessionBlocks(sessionId, principal.getId()).map(ApiResponse::success);
    }

    @PostMapping("/session/{sessionId}/blocks/finish")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Leave the current block", description = "The block closes for good; a break or the next block "
            + "follows. 409 BLOCK_NOT_FINISHABLE when every block is open or no block is being taken. The last block is left "
            + "by submitting the exam.")
    public Mono<ApiResponse<SessionBlocksResponse>> finishBlock(
            @PathVariable String sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return examService.finishBlock(sessionId, principal.getId()).map(ApiResponse::success);
    }

    @PostMapping("/session/{sessionId}/blocks/end-break")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "End the break early", description = "The next block starts now. 409 BREAK_NOT_SKIPPABLE when the "
            + "exam does not allow it.")
    public Mono<ApiResponse<SessionBlocksResponse>> endBreak(
            @PathVariable String sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return examService.endBreak(sessionId, principal.getId()).map(ApiResponse::success);
    }

    @PostMapping("/session/{sessionId}/blocks/reach/{questionId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Move on in a forward-only block", description = "The questions before this one close. 409 "
            + "QUESTION_LOCKED for a question already closed, BLOCK_CLOSED for one of another block.")
    public Mono<ApiResponse<SessionBlocksResponse>> reachQuestion(
            @PathVariable String sessionId,
            @PathVariable Long questionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return examService.reachQuestion(sessionId, questionId, principal.getId()).map(ApiResponse::success);
    }

    // ==================== Results ====================

    @GetMapping("/session/{sessionId}/result")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get session result", description = "Get the result of a completed session (owner only)")
    public Mono<ApiResponse<SessionResultResponse>> getSessionResult(
            @PathVariable String sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {

        log.debug("Getting result for session: {}", sessionId);

        return answerService.getSessionResult(sessionId, principal.getId())
                .map(ApiResponse::success);
    }

    @GetMapping("/{id}/results")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Get exam results", description = "Paginated sessions of an exam (admin or owning instructor) with "
            + "participant name and email, attempt number, score, gradingStatus, violationCount and riskLevel. "
            + "q: first name, last name, email prefix or a user id (at least 2 characters). "
            + "bestOnly (default true): one row per participant, the completed attempt with the highest percentage. "
            + "riskLevel (LOW, MEDIUM, HIGH, CRITICAL), minPercent / maxPercent (0..100, both inclusive) narrow the list; "
            + "sort=risk orders by risk level and score. Every row carries riskLevel and submitKind (MANUAL or "
            + "AUTO_EXPIRED). A call with only page, size and status is answered as before: every session, newest "
            + "submission first.")
    public Mono<ApiResponse<PageResponse<SessionSummaryResponse>>> getExamResults(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") @Parameter(description = "Page number (0-indexed)") int page,
            @RequestParam(defaultValue = "25") @Parameter(description = "Page size (max 50)") int size,
            @RequestParam(required = false) @Parameter(description = "Only sessions in this status") SessionStatus status,
            @RequestParam(required = false) @Parameter(description = "Participant name, email prefix or user id") String q,
            @RequestParam(required = false) @Parameter(description = "Only passed (true) or failed (false) sessions") Boolean passed,
            @RequestParam(required = false) @Parameter(description = "NOT_REQUIRED, PENDING or COMPLETE") GradingStatus gradingStatus,
            @RequestParam(required = false) @Parameter(description = "Only sessions with (true) or without (false) fraud events") Boolean hasViolations,
            @RequestParam(required = false) @Parameter(description = "One row per participant: the best completed attempt (default true)") Boolean bestOnly,
            @RequestParam(required = false) @Parameter(description = "score, name, violations, risk or submittedAt (default)") String sort,
            @RequestParam(required = false) @Parameter(description = "asc or desc (default: desc, for name asc)") String order,
            @RequestParam(required = false) @Parameter(description = "LOW, MEDIUM, HIGH or CRITICAL") RiskLevel riskLevel,
            @RequestParam(required = false) @Parameter(description = "Lowest percentage, 0..100") BigDecimal minPercent,
            @RequestParam(required = false) @Parameter(description = "Highest percentage, 0..100") BigDecimal maxPercent,
            @AuthenticationPrincipal UserPrincipal principal) {

        log.debug("Getting results of exam: {} for user: {}", id, principal.getId());

        ExamResultsService.ListQuery query = new ExamResultsService.ListQuery(q, status, passed, gradingStatus,
                hasViolations, bestOnly, sort, order, riskLevel, minPercent, maxPercent);
        return Mono.defer(() -> resultsService.getResults(id, query, page, size, principal.getId(), principal.getRole()))
                .map(ApiResponse::success);
    }

    @GetMapping("/{id}/results/export")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Export exam results as CSV or Excel", description = "format=xlsx: an Excel sheet for people (column "
            + "widths, a header that stays in view with filters, Baku time, Azerbaijani words, numbers as numbers, the session id last). "
            + "format=csv (default): the same list as /{id}/results with the same "
            + "parameters (without paging) as a CSV file: UTF-8 with BOM, comma separated, Azerbaijani headers, times in "
            + "UTC. At most 50 000 rows (422 RESULTS_EXPORT_TOO_LARGE). Cells starting with = + - @ get a leading "
            + "apostrophe. Same access as the results; every export leaves an audit row.")
    public Mono<ResponseEntity<Flux<DataBuffer>>> exportExamResults(
            @PathVariable Long id,
            @RequestParam(required = false) SessionStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean passed,
            @RequestParam(required = false) GradingStatus gradingStatus,
            @RequestParam(required = false) Boolean hasViolations,
            @RequestParam(required = false) Boolean bestOnly,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String order,
            @RequestParam(required = false) RiskLevel riskLevel,
            @RequestParam(required = false) BigDecimal minPercent,
            @RequestParam(required = false) BigDecimal maxPercent,
            @RequestParam(defaultValue = "csv") @Parameter(description = "csv or xlsx") String format,
            @AuthenticationPrincipal UserPrincipal principal) {

        ExamResultsService.ExportFormat exportFormat = "xlsx".equalsIgnoreCase(format)
                ? ExamResultsService.ExportFormat.XLSX : ExamResultsService.ExportFormat.CSV;
        ExamResultsService.ListQuery query = new ExamResultsService.ListQuery(q, status, passed, gradingStatus,
                hasViolations, bestOnly, sort, order, riskLevel, minPercent, maxPercent);
        return Mono.defer(() -> resultsService.export(id, query, principal.getId(), principal.getRole(), exportFormat))
                .doOnNext(export -> log.info("Results of exam {} exported by {}: {} rows", id, principal.getId(), export.rows()))
                .map(export -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(export.contentType()))
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                                .filename(export.fileName()).build().toString())
                        .header("X-Total-Count", String.valueOf(export.rows()))
                        .body(export.body()));
    }

    @GetMapping("/{id}/results/summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Get exam results summary", description = "Counts over all sessions, and average, pass rate, top and "
            + "lowest scorers and violation rankings over each participant's best completed attempt. Sessions that still "
            + "wait for a grader are counted in pendingGradingCount only.")
    public Mono<ApiResponse<ExamResultsSummaryResponse>> getExamResultsSummary(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {

        return resultsService.getSummary(id, principal.getId(), principal.getRole())
                .map(ApiResponse::success);
    }

    @GetMapping("/session/{sessionId}/review")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Review a session", description = "Everything about one session for the admin or the owning instructor: "
            + "participant, attempt number, score, every question with the answer key, what was answered and how it was graded, "
            + "media with preview URLs, and the fraud events (counts by type and up to 300 in order). Never for participants.")
    public Mono<ApiResponse<SessionReviewResponse>> getSessionReview(
            @PathVariable String sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {

        return resultsService.getReview(sessionId, principal.getId(), principal.getRole())
                .map(ApiResponse::success);
    }

    @GetMapping("/{id}/audience/preview")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Preview an exam audience", description = "How many participants the given groups reach: the members "
            + "of each group and how many different people that is (somebody in two groups counts once). Without groupIds the "
            + "exam's stored audience is used. Nothing is stored. Admin or owning instructor.")
    public Mono<ApiResponse<ExamAudiencePreviewResponse>> previewAudience(
            @PathVariable Long id,
            @RequestParam(required = false) @Parameter(description = "Group ids, comma separated; omitted = the stored audience") List<Long> groupIds,
            @AuthenticationPrincipal UserPrincipal principal) {

        return Mono.defer(() -> examService.previewAudience(id, groupIds, principal.getId(), principal.getRole()))
                .map(ApiResponse::success);
    }

    @PostMapping("/audience/preview")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Preview an audience without an exam", description = "The same counts as /{id}/audience/preview "
            + "for groups that are not stored yet (an exam being created): members per group, distinct participants "
            + "(somebody in two groups counts once) and unknown or deleted groups. At most 50 groups; nothing is stored. "
            + "For an INSTRUCTOR the groups of other owners are listed as unknown.")
    public Mono<ApiResponse<ExamAudiencePreviewResponse>> previewGroups(
            @Valid @RequestBody AudiencePreviewRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return Mono.defer(() -> examService.previewGroups(request.getGroupIds(), principal.getId(), principal.getRole()))
                .map(ApiResponse::success);
    }

    @PutMapping("/{id}/audience")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Set exam audience", description = "PUBLIC or GROUP with the groups whose members may take the exam (owner or admin). B13: only ADMIN makes an exam PUBLIC (403 PUBLIC_EXAM_ADMIN_ONLY); a teacher chooses only own groups (another owner's group is AUDIENCE_GROUP_NOT_FOUND like a missing one)")
    public Mono<ApiResponse<ExamAudienceResponse>> setAudience(
            @PathVariable Long id,
            @Valid @RequestBody ExamAudienceRequest request,
            @RequestParam(required = false) @Parameter(description = "Why (optional, 3-500 characters); the body field wins") String changeReason,
            ServerHttpRequest httpRequest,
            @AuthenticationPrincipal UserPrincipal principal) {

        log.info("Setting audience of exam: {} by user: {}", id, principal.getId());

        return changes.create(principal, httpRequest, request.getChangeReason(), changeReason)
                .flatMap(change -> examService.setAudience(id, request, change))
                .map(audience -> ApiResponse.success(audience, "Exam audience updated successfully"));
    }

    // ==================== Admin Operations ====================

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Publish an exam", description = "Change exam status to ACTIVE. An exam whose end time has passed "
            + "answers 400 EXAM_WINDOW_ENDED: give it a new end time with POST /{id}/reschedule first. "
            + "B12a: block timers, a block without free navigation or returning to earlier blocks need B12b: 409 FEATURE_NOT_AVAILABLE until it is switched on.")
    public Mono<ApiResponse<ExamResponse>> publishExam(
            @PathVariable Long id,
            @RequestParam(required = false) @Parameter(description = "Why (optional, 3-500 characters)") String changeReason,
            ServerHttpRequest httpRequest,
            @AuthenticationPrincipal UserPrincipal principal) {

        log.info("Publishing exam: {} by user: {}", id, principal.getId());

        return changes.create(principal, httpRequest, changeReason)
                .flatMap(change -> examService.updateExamStatus(id, ExamStatus.ACTIVE, change))
                .map(exam -> ApiResponse.success(exam, "Exam published successfully"));
    }

    @PostMapping("/{id}/reschedule")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Reschedule an exam", description = "Open a published exam again with a new time window "
            + "(SCHEDULED, ACTIVE, COMPLETED or ARCHIVED; a draft answers 400 EXAM_CANNOT_RESCHEDULE). Body: endTime "
            + "(required, in the future, after the start), optional startTime, maxAttempts, maxAttemptsPerDay "
            + "(null removes the daily limit). The exam becomes SCHEDULED when the start is still ahead, otherwise ACTIVE. "
            + "Questions, sessions and results are not touched. Admin or owning instructor. Answers the exam without its questions.")
    public Mono<ApiResponse<ExamResponse>> rescheduleExam(
            @PathVariable Long id,
            @Valid @RequestBody RescheduleExamRequest request,
            @RequestParam(required = false) @Parameter(description = "Why (optional, 3-500 characters); the body field wins") String changeReason,
            ServerHttpRequest httpRequest,
            @AuthenticationPrincipal UserPrincipal principal) {

        log.info("Rescheduling exam: {} by user: {}", id, principal.getId());

        return changes.create(principal, httpRequest, request.getChangeReason(), changeReason)
                .flatMap(change -> examService.rescheduleExam(id, request, change))
                .map(exam -> ApiResponse.success(exam, "Exam rescheduled successfully"));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Archive an exam", description = "Change exam status to ARCHIVED: hidden from participants and from the "
            + "default management list, every attempt and result is kept, sessions in progress can be finished. "
            + "POST /{id}/reschedule brings it back.")
    public Mono<ApiResponse<ExamResponse>> archiveExam(
            @PathVariable Long id,
            @RequestParam(required = false) @Parameter(description = "Why (optional, 3-500 characters)") String changeReason,
            ServerHttpRequest httpRequest,
            @AuthenticationPrincipal UserPrincipal principal) {

        log.info("Archiving exam: {} by user: {}", id, principal.getId());

        return changes.create(principal, httpRequest, changeReason)
                .flatMap(change -> examService.updateExamStatus(id, ExamStatus.ARCHIVED, change))
                .map(exam -> ApiResponse.success(exam, "Exam archived successfully"));
    }

    @PatchMapping("/{id}/meta")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Change title or description", description = "Only title (3-255 characters) and description "
            + "(at most 5000; empty removes it); a field left out stays. Works in every status, also when the exam has "
            + "attempts: nothing that decides a result changes. Any other field is 400. ADMIN and the INSTRUCTOR who "
            + "created the exam; one audit row. The answer is the exam without questions.")
    public Mono<ApiResponse<ExamResponse>> updateMeta(
            @PathVariable Long id,
            @Valid @RequestBody ExamMetaRequest request,
            @RequestParam(required = false) @Parameter(description = "Why (optional, 3-500 characters); the body field wins") String changeReason,
            ServerHttpRequest httpRequest,
            @AuthenticationPrincipal UserPrincipal principal) {

        return changes.create(principal, httpRequest, request.getChangeReason(), changeReason)
                .flatMap(change -> examMetaService.updateMeta(id, request, change))
                .map(exam -> ApiResponse.success(exam, "Exam title and description updated"));
    }
}
