package com.examportal.exam.controller;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.history.ExamHistoryDtos;
import com.examportal.common.security.UserPrincipal;
import com.examportal.exam.history.ExamHistoryQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * The history of an exam and its published versions (B12a). ADMIN every exam, an INSTRUCTOR the own one
 * (anybody else's 404), STUDENT 403 (SecurityConfig by path, and here).
 */
@RestController
@RequestMapping("/api/exams/{id}")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
@Tag(name = "Exam History", description = "What happened to an exam, by whom, and every published version")
@SecurityRequirement(name = "bearerAuth")
public class ExamHistoryController {

    private final ExamHistoryQueryService historyService;

    @GetMapping("/history")
    @Operation(summary = "History of an exam", description = "Newest first, paged (default 50, max 100). One row per changed "
            + "thing; rows of one save share saveId. The frontend builds the sentence from summaryKey and summaryParams "
            + "(actorName, actorCode, actorRole, questionNumber, blockTitle, blockNumber, questionPreview, changedFields, ...). "
            + "Filters: actor (user id or 7-digit code), action, from/to (a day yyyy-MM-dd, to inclusive, or a UTC moment), "
            + "sensitiveOnly, entityType (EXAM, SETTINGS, AUDIENCE, BLOCK, QUESTION, OPTION). Readable after the exam was deleted.")
    public Mono<ApiResponse<PageResponse<ExamHistoryDtos.Entry>>> history(
            @PathVariable Long id,
            @RequestParam(required = false) @Parameter(description = "User id or 7-digit participant code") String actor,
            @RequestParam(required = false) @Parameter(description = "e.g. QUESTION_UPDATED, PUBLISHED") String action,
            @RequestParam(required = false) @Parameter(description = "yyyy-MM-dd or a UTC moment") String from,
            @RequestParam(required = false) @Parameter(description = "yyyy-MM-dd (inclusive) or a UTC moment (exclusive)") String to,
            @RequestParam(required = false) @Parameter(description = "Only sensitive changes") Boolean sensitiveOnly,
            @RequestParam(required = false) @Parameter(description = "EXAM, SETTINGS, AUDIENCE, BLOCK, QUESTION or OPTION") String entityType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal UserPrincipal principal) {
        return historyService.page(id, principal.getId(), principal.getRole(), actor, action, from, to, sensitiveOnly,
                        entityType, page, size)
                .map(ApiResponse::success);
    }

    @GetMapping("/history/{revisionId}")
    @Operation(summary = "One history row in full", description = "With changes: {field: [before, after]}; a deleted "
            + "question or exam is in full. ip, userAgent and requestId only for ADMIN.")
    public Mono<ApiResponse<ExamHistoryDtos.Entry>> historyEntry(
            @PathVariable Long id,
            @PathVariable Long revisionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return historyService.one(id, revisionId, principal.getId(), principal.getRole())
                .map(ApiResponse::success);
    }

    @GetMapping("/versions")
    @Operation(summary = "Published versions", description = "One version per publication, newest first: versionNo, "
            + "sha256 of its canonical JSON, who and when")
    public Mono<ApiResponse<List<ExamHistoryDtos.Version>>> versions(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return historyService.versions(id, principal.getId(), principal.getRole())
                .map(ApiResponse::success);
    }

    @GetMapping("/versions/{versionNo}")
    @Operation(summary = "One published version", description = "The whole exam as it was published (settings, audience, "
            + "blocks, questions, options with the answer key, media ids), its sha256 and hashValid (checked again now)")
    public Mono<ApiResponse<ExamHistoryDtos.Version>> version(
            @PathVariable Long id,
            @PathVariable int versionNo,
            @AuthenticationPrincipal UserPrincipal principal) {
        return historyService.version(id, versionNo, principal.getId(), principal.getRole())
                .map(ApiResponse::success);
    }
}
