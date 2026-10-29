package com.examportal.paid.catalog;

import com.examportal.paid.PaidErrors;
import com.examportal.paid.exam.PaidExamRepository;
import com.examportal.paid.exam.PaidExamSettings;
import com.examportal.paid.provider.SandboxProductionGuard;
import com.examportal.paid.web.PaidDtos;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * B13b item 2: the public catalog of paid open exams, without sign-in: only PAID_OPEN, published (SCHEDULED or
 * ACTIVE), not deleted and with the sales open now. Title, description, start and end, duration, price and seats
 * left; never a question, an answer or an internal field. Anything else is the same 404 as an unknown id.
 */
@Service
public class PaidCatalogService {

    public static final int MAX_PAGE_SIZE = 50;

    private final PaidExamRepository exams;
    private final SandboxProductionGuard guard;

    /** Outside production (tests): paymentsDemo as PAYMENTS_SANDBOX_ALLOWED would make it there, here false */
    public PaidCatalogService(PaidExamRepository exams) {
        this(exams, SandboxProductionGuard.development());
    }

    @Autowired
    public PaidCatalogService(PaidExamRepository exams, SandboxProductionGuard guard) {
        this.exams = exams;
        this.guard = guard;
    }

    public Mono<PaidDtos.CatalogPage> page(int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageNumber = Math.max(page, 0);
        LocalDateTime now = LocalDateTime.now();
        return Mono.zip(exams.findOnSale(now, pageSize, (long) pageNumber * pageSize).concatMap(this::view).collectList(),
                        exams.countOnSale(now))
                .map(found -> new PaidDtos.CatalogPage(found.getT1(), found.getT2(), pageNumber, pageSize, guard.paymentsDemo()));
    }

    public Mono<PaidDtos.CatalogExam> one(Long examId) {
        return exams.findOnSale(examId, LocalDateTime.now())
                .switchIfEmpty(Mono.error(PaidErrors.examNotFound()))
                .flatMap(this::view);
    }

    private Mono<PaidDtos.CatalogExam> view(PaidExamSettings exam) {
        return Mono.zip(exams.description(exam.examId()), exams.durationMinutes(exam.examId()))
                .map(more -> new PaidDtos.CatalogExam(exam.examId(), exam.title(), more.getT1(), exam.startTime(), exam.endTime(),
                        more.getT2(), exam.priceMinor(), exam.currency(), exam.seatsLeft(), exam.salesCloseAt(), guard.paymentsDemo()));
    }
}
