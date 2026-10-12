package com.examportal.exam.controller;

import com.examportal.common.domain.dto.*;
import com.examportal.common.domain.entity.ExamStatus;
import com.examportal.common.security.UserPrincipal;
import com.examportal.exam.service.AnswerSubmissionService;
import com.examportal.exam.service.ExamService;
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

    // ==================== Exam CRUD Operations ====================

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Create a new exam", description = "Create a new exam with questions and options")
    public Mono<ApiResponse<ExamResponse>> createExam(
            @Valid @RequestBody CreateExamRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        
        log.info("Creating exam: {} by user: {}", request.getTitle(), principal.getId());
        
        return examService.createExam(request, principal.getId())
                .map(exam -> ApiResponse.success(exam, "Exam created successfully"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Update an exam", description = "Update an existing exam")
    public Mono<ApiResponse<ExamResponse>> updateExam(
            @PathVariable Long id,
            @Valid @RequestBody UpdateExamRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        
        log.info("Updating exam: {} by user: {}", id, principal.getId());
        
        return examService.updateExam(id, request, principal.getId())
                .map(exam -> ApiResponse.success(exam, "Exam updated successfully"));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Delete an exam", description = "Delete an exam (soft delete)")
    public Mono<Void> deleteExam(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        
        log.info("Deleting exam: {} by user: {}", id, principal.getId());
        
        return examService.deleteExam(id, principal.getId());
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

    @GetMapping("/available")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR', 'STUDENT')")
    @Operation(summary = "Get available exams", description = "Get exams available for participation")
    public Flux<ExamResponse> getAvailableExams(
            @AuthenticationPrincipal UserPrincipal principal) {
        
        log.debug("Getting available exams for user: {}", principal.getId());
        
        return examService.getAvailableExams(principal.getId());
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
        
        return examService.startExam(id, principal.getId(), startRequest)
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
    @Operation(summary = "Get exam results", description = "Paginated sessions of an exam (admin or owning instructor)")
    public Mono<ApiResponse<PageResponse<SessionSummaryResponse>>> getExamResults(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") @Parameter(description = "Page number (0-indexed)") int page,
            @RequestParam(defaultValue = "25") @Parameter(description = "Page size (max 50)") int size,
            @AuthenticationPrincipal UserPrincipal principal) {

        log.debug("Getting results of exam: {} for user: {}", id, principal.getId());

        return examService.getExamResults(id, principal.getId(), principal.getRole(), page, size)
                .map(ApiResponse::success);
    }

    // ==================== Admin Operations ====================

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Publish an exam", description = "Change exam status to ACTIVE")
    public Mono<ApiResponse<ExamResponse>> publishExam(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        
        log.info("Publishing exam: {} by user: {}", id, principal.getId());
        
        return examService.updateExamStatus(id, ExamStatus.ACTIVE, principal.getId())
                .map(exam -> ApiResponse.success(exam, "Exam published successfully"));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Archive an exam", description = "Change exam status to ARCHIVED")
    public Mono<ApiResponse<ExamResponse>> archiveExam(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        
        log.info("Archiving exam: {} by user: {}", id, principal.getId());
        
        return examService.updateExamStatus(id, ExamStatus.ARCHIVED, principal.getId())
                .map(exam -> ApiResponse.success(exam, "Exam archived successfully"));
    }
}
