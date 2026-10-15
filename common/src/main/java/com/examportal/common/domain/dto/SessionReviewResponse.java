package com.examportal.common.domain.dto;

import com.examportal.common.domain.dto.media.MediaItemResponse;
import com.examportal.common.domain.entity.FraudEventType;
import com.examportal.common.domain.entity.GradingStatus;
import com.examportal.common.domain.entity.QuestionType;
import com.examportal.common.domain.entity.RiskLevel;
import com.examportal.common.domain.entity.SessionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Session Review Response DTO
 * Everything about one session for ADMIN and the INSTRUCTOR who owns the exam: the participant,
 * every question with the answer key and what was answered, the grading, and the fraud events.
 * Carries the answer key, so it never goes to a participant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionReviewResponse {

    private String sessionId;
    private Long examId;
    private String examTitle;
    private Participant participant;
    // Which of the participant's sessions for this exam this is (1 = the first)
    private int attemptNumber;
    private SessionStatus status;
    private LocalDateTime startedAt;
    private LocalDateTime endTime;
    private LocalDateTime submittedAt;
    private Long durationSeconds;
    // Provisional while gradingStatus is PENDING
    private BigDecimal score;
    private BigDecimal maxScore;
    private BigDecimal percentage;
    private Boolean passed;
    private BigDecimal passingScore;
    private GradingStatus gradingStatus;
    private List<QuestionReview> questions;
    private Violations violations;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Participant {
        private UUID id;
        private String name;
        private String email;
        // 7-digit participant code (display and search only, never security)
        private Integer participantCode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionReview {
        private Long questionId;
        private Integer orderIndex;
        private String questionText;
        private QuestionType questionType;
        private BigDecimal maxPoints;
        private String explanation;
        private List<MediaItemResponse> media;
        // Choice questions: every option; text questions: the expected answers
        private List<OptionReview> options;

        // What the participant did
        private boolean answered;
        private Long answerId;
        private List<Long> selectedOptionIds;
        private String textAnswer;
        private LocalDateTime answeredAt;
        private BigDecimal pointsEarned;
        // Null for an unanswered question and for an open-ended answer that is not graded yet
        private Boolean correct;

        // Manual grading (open-ended answers)
        private boolean needsManualReview;
        private String feedback;
        private String gradedBy;
        private UUID gradedById;
        private LocalDateTime gradedAt;
        private Integer gradeVersion;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptionReview {
        private Long id;
        private String optionText;
        private Integer orderIndex;
        private boolean correct;
        private boolean selected;
        private List<MediaItemResponse> media;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Violations {
        private int total;
        private int riskScore;
        private RiskLevel riskLevel;
        private Map<FraudEventType, Integer> countByType;
        // In the order they happened; at most 300
        private List<ViolationEvent> events;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ViolationEvent {
        private FraudEventType type;
        private LocalDateTime occurredAt;
        private String details;
    }
}
