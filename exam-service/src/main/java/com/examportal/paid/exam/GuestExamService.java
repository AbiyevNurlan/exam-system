package com.examportal.paid.exam;

import com.examportal.common.domain.dto.StartExamRequest;
import com.examportal.common.domain.dto.StartExamResponse;
import com.examportal.exam.service.ExamService;
import com.examportal.paid.PaidErrors;
import com.examportal.paid.guest.GuestDirectory;
import com.examportal.paid.order.EntitlementRepository;
import com.examportal.paid.security.PaidGuest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * B13b item 9: a guest starts a paid open exam. The guest must not be blocked, the exam must be PAID_OPEN (any other
 * exam is the same 404 as an unknown one: a guest never takes an AUDIENCE exam) and the guest needs an ACTIVE
 * entitlement with an attempt left, taken atomically at the start (ExamService.startPaidExam). Everything after the
 * start is the session of today (questions, answers, blocks, submit, the result and its publication rules), through
 * the guest's own paths.
 */
@Service
@RequiredArgsConstructor
public class GuestExamService {

    private final GuestDirectory directory;
    private final ExamService examService;
    private final EntitlementRepository entitlements;

    public Mono<StartExamResponse> start(PaidGuest guest, Long examId, StartExamRequest request) {
        return directory.active(guest.id())
                .then(examService.startPaidExam(examId, guest.id(), request,
                        () -> entitlements.takeAttempt(guest.id(), examId), PaidErrors::notEntitled));
    }
}
