package com.examportal.common.domain.dto.history;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * The history of an exam and its published versions (B12a): GET /api/exams/{id}/history[/{revisionId}],
 * GET /api/exams/{id}/versions[/{n}]. The sentence of a row is built by the frontend in az/ru/en from
 * summaryKey and summaryParams; changes holds {field: [before, after]} (only in the detail).
 */
public final class ExamHistoryDtos {

    private ExamHistoryDtos() {
    }

    /**
     * Who did it, as auth-service knew the person at that moment
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Actor {
        private UUID userId;
        private String name;
        // 7-digit participant code (display and search only)
        private Integer code;
        private String role;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Entry {
        private Long id;
        private Long examId;
        private Integer revisionNo;
        // Every row of one save ("Saxla") shares it
        private UUID saveId;
        private LocalDateTime createdAt;
        private String action;
        private String entityType;
        private Long entityId;
        private Boolean sensitive;
        private String reason;
        private String summaryKey;
        private Map<String, Object> summaryParams;
        private Actor actor;
        // Detail only: {field: [before, after]}
        private Object changes;
        // Detail only, ADMIN only
        private String ip;
        private String userAgent;
        private String requestId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Version {
        private Long examId;
        private Integer versionNo;
        private String sha256;
        private LocalDateTime createdAt;
        private Actor actor;
        // Detail only: the whole exam as published, and whether it still has its hash
        private Object snapshot;
        private Boolean hashValid;
    }
}
