package com.examportal.exam.controller;

import com.examportal.common.domain.dto.*;
import com.examportal.common.domain.dto.group.ExamAudienceRequest;
import com.examportal.common.domain.dto.group.ExamAudienceResponse;
import com.examportal.common.domain.dto.media.MediaUrlResponse;
import com.examportal.common.domain.entity.ExamStatus;
import com.examportal.common.domain.entity.GradingStatus;
import com.examportal.common.domain.entity.SessionStatus;
import com.examportal.common.security.UserPrincipal;
import com.examportal.exam.service.AnswerSubmissionService;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
    private final AnswerSubmissionService answerService;
    private final SessionMediaService sessionMediaService;
    private final FraudEventService fraudEventService;
    private final ExamResultsService resultsService;

    // ==================== Exam CRUD Operations ====================

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Create a new exam", description = "Create a new exam with questions and options. Questions may carry media (up to 3: {mediaId, position, maxPlays for audio/video}) and options one image (mediaIds); only your own confirmed media. WARNING: the alt text of option media is shown to participants and must not reveal the answer.")
    public Mono<ApiResponse<ExamResponse>> createExam(
            @Valid @RequestBody CreateExamRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        
        log.info("Creating exam: {} by user: {}", request.getTitle(), principal.getId());
        
        return examService.createExam(request, principal.getId(), principal.getRole())
                .map(exam -> ApiResponse.success(exam, "Exam created successfully"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Update an exam", description = "Update an existing exam (DRAFT or SCHEDULED only; questions, options and their media are replaced). Questions may carry media (up to 3: {mediaId, position, maxPlays for audio/video}) and options one image (mediaIds); your own confirmed media, or media the exam already carries (ADMIN: any). WARNING: the alt text of option media is shown to participants and must not reveal the answer.")
    public Mono<ApiResponse<ExamResponse>> updateExam(
            @PathVariable Long id,
            @Valid @RequestBody UpdateExamRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        
        log.info("Updating exam: {} by user: {}", id, principal.getId());
        
        return examService.updateExam(id, request, principal.getId(), principal.getRole())
                .map(exam -> ApiResponse.success(exam, "Exam updated successfully"));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Delete an exam", description = "Removes an exam for good, with its questions, options, media links "
            + "and audience. Only an exam without any session: otherwise 409 EXAM_CANNOT_DELETE and nothing is removed; archive it instead.")
    public Mono<Void> deleteExam(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        
        log.info("Deleting exam: {} by user: {}", id, principal.getId());
        
        return examService.deleteExam(id, principal.getId(), principal.getRole());
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
            + "A call with only page, size and status is answered as before: every session, newest submission first.")
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
            @RequestParam(required = false) @Parameter(description = "score, name, violations or submittedAt (default)") String sort,
            @RequestParam(required = false) @Parameter(description = "asc or desc (default: desc, for name asc)") String order,
            @AuthenticationPrincipal UserPrincipal principal) {

        log.debug("Getting results of exam: {} for user: {}", id, principal.getId());

        ExamResultsService.ListQuery query = new ExamResultsService.ListQuery(q, status, passed, gradingStatus,
                hasViolations, bestOnly, sort, order);
        return Mono.defer(() -> resultsService.getResults(id, query, page, size, principal.getId(), principal.getRole()))
                .map(ApiResponse::success);
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

    @PutMapping("/{id}/audience")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Set exam audience", description = "PUBLIC or GROUP with the groups whose members may take the exam (owner or admin)")
    public Mono<ApiResponse<ExamAudienceResponse>> setAudience(
            @PathVariable Long id,
            @Valid @RequestBody ExamAudienceRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        log.info("Setting audience of exam: {} by user: {}", id, principal.getId());

        return examService.setAudience(id, request, principal.getId(), principal.getRole())
                .map(audience -> ApiResponse.success(audience, "Exam audience updated successfully"));
    }

    // ==================== Admin Operations ====================

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Publish an exam", description = "Change exam status to ACTIVE")
    public Mono<ApiResponse<ExamResponse>> publishExam(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        
        log.info("Publishing exam: {} by user: {}", id, principal.getId());
        
        return examService.updateExamStatus(id, ExamStatus.ACTIVE, principal.getId(), principal.getRole())
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
            @AuthenticationPrincipal UserPrincipal principal) {

        log.info("Rescheduling exam: {} by user: {}", id, principal.getId());

        return examService.rescheduleExam(id, request, principal.getId(), principal.getRole())
                .map(exam -> ApiResponse.success(exam, "Exam rescheduled successfully"));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Archive an exam", description = "Change exam status to ARCHIVED: hidden from participants and from the "
            + "default management list, every attempt and result is kept, sessions in progress can be finished. "
            + "POST /{id}/reschedule brings it back.")
    public Mono<ApiResponse<ExamResponse>> archiveExam(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        
        log.info("Archiving exam: {} by user: {}", id, principal.getId());
        
        return examService.updateExamStatus(id, ExamStatus.ARCHIVED, principal.getId(), principal.getRole())
                .map(exam -> ApiResponse.success(exam, "Exam archived successfully"));
    }
}
