package com.examportal.paid.web;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.domain.dto.FraudEventRequest;
import com.examportal.common.domain.dto.media.MediaUrlResponse;
import com.examportal.common.domain.dto.MySessionResponse;
import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.QuestionResponse;
import com.examportal.common.domain.dto.SavedAnswerResponse;
import com.examportal.common.domain.dto.SessionBlocksResponse;
import com.examportal.common.domain.dto.SessionResultResponse;
import com.examportal.common.domain.dto.StartExamRequest;
import com.examportal.common.domain.dto.StartExamResponse;
import com.examportal.common.domain.dto.SubmitAnswerRequest;
import com.examportal.common.domain.dto.SubmitAnswerResponse;
import com.examportal.exam.service.AnswerSubmissionService;
import com.examportal.exam.service.ExamService;
import com.examportal.exam.service.FraudEventService;
import com.examportal.exam.service.SessionMediaService;
import com.examportal.paid.exam.GuestExamService;
import com.examportal.paid.security.PaidGuest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * B13b item 9: the guest's exam (GUEST only). The start needs an ACTIVE entitlement with an attempt left (403
 * NOT_ENTITLED); a guest reaches only PAID_OPEN exams (any other is 404). The session paths are those of /api/exams,
 * served by the same services with the guest as the owner of the session: another's session is 404, the result follows
 * the publication rules of the exam (and the block rule of B12a). The accounts' /api/exams paths stay closed to guests.
 */
@RestController
@RequestMapping("/api/guest")
@RequiredArgsConstructor
@PreAuthorize("hasRole('GUEST')")
@Tag(name = "Guest exams", description = "Taking a paid open exam")
public class GuestExamController {

    private final GuestExamService guestExams;
    private final ExamService examService;
    private final AnswerSubmissionService answerService;
    private final SessionMediaService sessionMediaService;
    private final FraudEventService fraudEventService;

    @PostMapping("/exams/{examId}/start")
    @Operation(summary = "Start a paid exam", description = "One attempt of the entitlement is taken (an open session is answered "
            + "again without one); 403 NOT_ENTITLED, 404 for an exam that is not PAID_OPEN, 409 EXAM_NOT_ACTIVE outside the window")
    public Mono<ApiResponse<StartExamResponse>> start(@AuthenticationPrincipal PaidGuest guest, @PathVariable Long examId,
                                                      @Valid @RequestBody(required = false) StartExamRequest request) {
        return guestExams.start(guest, examId, request).map(started -> ApiResponse.success(started, "Exam started successfully"));
    }

    @GetMapping("/sessions/{sessionId}/questions")
    public Mono<ApiResponse<List<QuestionResponse>>> questions(@AuthenticationPrincipal PaidGuest guest, @PathVariable String sessionId,
                                                               @RequestParam(defaultValue = "0") int batch) {
        return examService.getNextQuestions(sessionId, batch, guest.id()).map(ApiResponse::success);
    }

    @GetMapping("/sessions/{sessionId}/media/{mediaId}")
    public Mono<ApiResponse<MediaUrlResponse>> media(@AuthenticationPrincipal PaidGuest guest, @PathVariable String sessionId,
                                                     @PathVariable UUID mediaId) {
        return sessionMediaService.getMediaUrl(sessionId, mediaId, guest.id()).map(ApiResponse::success);
    }

    @GetMapping("/sessions/{sessionId}/answers")
    public Mono<ApiResponse<List<SavedAnswerResponse>>> answers(@AuthenticationPrincipal PaidGuest guest, @PathVariable String sessionId) {
        return answerService.getSavedAnswers(sessionId, guest.id()).map(ApiResponse::success);
    }

    @PostMapping("/sessions/{sessionId}/fraud-event")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<ApiResponse<Void>> fraudEvent(@AuthenticationPrincipal PaidGuest guest, @PathVariable String sessionId,
                                              @Valid @RequestBody FraudEventRequest request) {
        return fraudEventService.record(sessionId, request, guest.id())
                .then(Mono.fromSupplier(() -> ApiResponse.<Void>success(null, "Event received")));
    }

    @PostMapping("/sessions/{sessionId}/answer")
    public Mono<ApiResponse<SubmitAnswerResponse>> answer(@AuthenticationPrincipal PaidGuest guest, @PathVariable String sessionId,
                                                          @Valid @RequestBody SubmitAnswerRequest request) {
        return answerService.submitAnswer(sessionId, request, guest.id()).map(ApiResponse::success);
    }

    @PostMapping("/sessions/{sessionId}/submit")
    public Mono<ApiResponse<SessionResultResponse>> submit(@AuthenticationPrincipal PaidGuest guest, @PathVariable String sessionId) {
        return answerService.submitExam(sessionId, guest.id()).map(result -> ApiResponse.success(result, "Exam submitted successfully"));
    }

    @GetMapping("/sessions/{sessionId}/blocks")
    public Mono<ApiResponse<SessionBlocksResponse>> blocks(@AuthenticationPrincipal PaidGuest guest, @PathVariable String sessionId) {
        return examService.sessionBlocks(sessionId, guest.id()).map(ApiResponse::success);
    }

    @PostMapping("/sessions/{sessionId}/blocks/finish")
    public Mono<ApiResponse<SessionBlocksResponse>> finishBlock(@AuthenticationPrincipal PaidGuest guest, @PathVariable String sessionId) {
        return examService.finishBlock(sessionId, guest.id()).map(ApiResponse::success);
    }

    @PostMapping("/sessions/{sessionId}/blocks/end-break")
    public Mono<ApiResponse<SessionBlocksResponse>> endBreak(@AuthenticationPrincipal PaidGuest guest, @PathVariable String sessionId) {
        return examService.endBreak(sessionId, guest.id()).map(ApiResponse::success);
    }

    @PostMapping("/sessions/{sessionId}/blocks/reach/{questionId}")
    public Mono<ApiResponse<SessionBlocksResponse>> reach(@AuthenticationPrincipal PaidGuest guest, @PathVariable String sessionId,
                                                          @PathVariable Long questionId) {
        return examService.reachQuestion(sessionId, questionId, guest.id()).map(ApiResponse::success);
    }

    @GetMapping("/results")
    @Operation(summary = "The guest's own results", description = "Newest first; scores only where the exam's publication rule allows")
    public Mono<ApiResponse<PageResponse<MySessionResponse>>> results(@AuthenticationPrincipal PaidGuest guest,
                                                                      @RequestParam(defaultValue = "0") int page,
                                                                      @RequestParam(defaultValue = "20") int size) {
        return answerService.getMySessions(guest.id(), page, size).map(ApiResponse::success);
    }

    @GetMapping("/results/{sessionId}")
    @Operation(summary = "One own result", description = "Another's session is 404; the exam's publication rule applies")
    public Mono<ApiResponse<SessionResultResponse>> result(@AuthenticationPrincipal PaidGuest guest, @PathVariable String sessionId) {
        return answerService.getSessionResult(sessionId, guest.id()).map(ApiResponse::success);
    }
}
