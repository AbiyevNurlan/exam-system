package com.examportal.paid.web;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.paid.catalog.PaidCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * B13b item 2: the public catalog of paid open exams, without sign-in (PaidSecurityConfig); its own rate-limit bucket
 * at the gateway (paid-public)
 */
@RestController
@RequestMapping("/api/public/paid-exams")
@RequiredArgsConstructor
@Tag(name = "Paid exams (public)", description = "The catalog of paid open exams")
public class PaidCatalogController {

    private final PaidCatalogService catalog;

    @GetMapping
    @Operation(summary = "Paid open exams on sale", description = "Title, description, start and end, duration, price (whole "
            + "minor units) and seats left; never questions or internal fields")
    public Mono<ApiResponse<PaidDtos.CatalogPage>> list(@RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        return catalog.page(page, size).map(ApiResponse::success);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One paid open exam on sale", description = "404 EXAM_NOT_FOUND for anything not on sale")
    public Mono<ApiResponse<PaidDtos.CatalogExam>> one(@PathVariable Long id) {
        return catalog.one(id).map(ApiResponse::success);
    }
}
