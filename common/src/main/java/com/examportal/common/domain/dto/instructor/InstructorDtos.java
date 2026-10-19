package com.examportal.common.domain.dto.instructor;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * B13: teachers (INSTRUCTOR accounts) managed by an administrator. The account lives in auth-service
 * (POST/GET/PATCH /api/admin/instructors, reset-password), what a teacher owns and the limits in exam-service
 * (figures over /internal/instructors/figures, GET/PUT /api/admin/instructor-limits/{id}).
 */
public final class InstructorDtos {

    private InstructorDtos() {
    }

    /** POST /api/admin/instructors */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        @NotBlank(message = "First name is required")
        @Size(min = 2, max = 100, message = "First name must be between 2 and 100 characters")
        private String firstName;

        @NotBlank(message = "Last name is required")
        @Size(min = 2, max = 100, message = "Last name must be between 2 and 100 characters")
        private String lastName;

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        @Size(max = 255, message = "Email must not exceed 255 characters")
        private String email;
    }

    /**
     * The answer of a create or a reset: the only place the temporary password is ever shown. It is not stored
     * (only its hash), not logged, not audited and not cached.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Created {
        private UUID id;
        private Integer participantCode;
        private String firstName;
        private String lastName;
        private String email;
        private String role;
        private boolean active;
        private boolean mustChangePassword;
        private String temporaryPassword;
        private LocalDateTime temporaryPasswordExpiresAt;
    }

    /** PATCH /api/admin/instructors/{id}: a field left out keeps its value */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        @Size(min = 2, max = 100, message = "First name must be between 2 and 100 characters")
        private String firstName;

        @Size(min = 2, max = 100, message = "Last name must be between 2 and 100 characters")
        private String lastName;

        private Boolean active;
    }

    /** One teacher of GET /api/admin/instructors and the answer of PATCH */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Row {
        private UUID id;
        private Integer participantCode;
        private String firstName;
        private String lastName;
        private String email;
        /** ACTIVE, DEACTIVATED, or PASSWORD_CHANGE_REQUIRED (active, temporary password not changed yet) */
        private String status;
        private boolean active;
        private boolean mustChangePassword;
        private LocalDateTime temporaryPasswordExpiresAt;
        private LocalDateTime createdAt;
        private LocalDateTime lastLoginAt;
        /** From exam-service; null when it could not be asked (figuresAvailable false) */
        private Long groupCount;
        private Long memberCount;
        private Long examCount;
        /** Exams that are ACTIVE or SCHEDULED */
        private Long activeExamCount;
        private boolean figuresAvailable;
    }

    /** Service to service: what each teacher owns in exam-service */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FiguresRequest {
        @NotNull
        @Size(max = 500, message = "At most 500 teachers at once")
        private List<UUID> ids;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FiguresResponse {
        private List<Figures> figures;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Figures {
        private UUID id;
        private long groupCount;
        private long memberCount;
        private long examCount;
        private long activeExamCount;
    }

    /**
     * GET/PUT /api/admin/instructor-limits/{id}: the limits of one teacher. A null field in a PUT means the system
     * default; the answer shows the limit in force (own or default), whether it is the default, and what is used.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Limits {
        private UUID instructorId;
        @Positive(message = "maxGroups must be positive")
        private Integer maxGroups;
        @Positive(message = "maxMembers must be positive")
        private Integer maxMembers;
        @Positive(message = "maxExams must be positive")
        private Integer maxExams;
        @Positive(message = "maxMediaBytes must be positive")
        private Long maxMediaBytes;
        /** In the answer: which of the four are the system default */
        private List<String> defaults;
        /** In the answer: what the teacher uses now */
        private Long usedGroups;
        private Long usedMembers;
        private Long usedExams;
        private Long usedMediaBytes;
    }
}
