package com.examportal.common.domain.dto.profile;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Participant Profile DTOs (B11)
 * The extendable profile of a participant: a category (participant_categories, a lookup table, so new
 * kinds come without a code change) and optional details. The profile lives in auth-service
 * (participant_profiles); exam-service relays it for GET/PUT /api/me/profile and shows it on the
 * admin's participant card. An account without a profile has profile = null: not an error.
 */
public final class ParticipantProfileDtos {

    // Text limits after cleaning (ProfileText); the columns of V4 have exactly these lengths
    public static final int MAX_INSTITUTION = 200;
    public static final int MAX_FACULTY_OR_DEPARTMENT = 200;
    public static final int MAX_COURSE_OR_GRADE = 50;
    public static final int MAX_WORKPLACE = 200;
    public static final int MAX_POSITION = 100;
    public static final int MAX_CITY = 100;
    public static final int MAX_CATEGORY = 40;

    // The detail fields by their JSON name, in the order they are shown and compared
    public static final List<String> DETAIL_FIELDS = List.of(
            "institution", "facultyOrDepartment", "courseOrGrade", "workplace", "position", "city", "birthDate");

    private ParticipantProfileDtos() {
    }

    /**
     * The stored profile. updatedByAdmin: the last change was made by an administrator, not by the participant.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Profile {
        private String category;
        private String institution;
        private String facultyOrDepartment;
        private String courseOrGrade;
        private String workplace;
        private String position;
        private String city;
        private LocalDate birthDate;
        private LocalDateTime updatedAt;
        private boolean updatedByAdmin;
    }

    /**
     * PUT /api/me/profile and PATCH /api/admin/participants/{id}/profile: the whole profile. category is
     * required; every other field is optional (unless participant-profile.required-fields says otherwise for
     * the category), and a field left out or empty is stored as empty. Text is trimmed and cleaned of HTML.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private String category;
        private String institution;
        private String facultyOrDepartment;
        private String courseOrGrade;
        private String workplace;
        private String position;
        private String city;
        private LocalDate birthDate;
    }

    /**
     * GET /api/me/profile: the own profile (null when there is none yet) and the categories that may be chosen
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class MyProfile {
        private Profile profile;
        private List<String> categories;
        // Per category the fields that must be filled (configuration); categories without an entry need none
        private java.util.Map<String, List<String>> requiredFields;
    }
}
