package com.examportal.common.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * GET /api/exams/{id}/readiness (B12a): what stands in the way of publishing (blocking) and what deserves a look
 * (warnings). Publishing runs the same check: anything blocking is 409 EXAM_NOT_READY with this list (block timers
 * and fixed navigation before B12b: 409 FEATURE_NOT_AVAILABLE). messageKey is for the frontend's texts (az/ru/en).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExamReadinessResponse {

    private Long examId;
    private boolean ready;
    private List<Item> blocking;
    private List<Item> warnings;
    private Integer totalMinutes;
    private Long windowMinutes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Item {
        private String code;
        private String messageKey;
        // Where: a block and/or a question (with their numbers as participants see them)
        private Long blockId;
        private Integer blockNumber;
        private Long questionId;
        private Integer questionNumber;
        private Map<String, Object> params;
    }
}
