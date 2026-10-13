package com.examportal.common.domain.dto.analytics;

import com.examportal.common.domain.entity.QuestionType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Question Analytics Response DTO
 * How the questions of one exam were answered. Everything is counted over each participant's best
 * completed attempt; sessions that still wait for a grader are left out and counted separately.
 * Rates are fractions between 0 and 1.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionAnalyticsResponse {

    private Long examId;
    // When the figures were computed (UTC); they may be up to a minute old
    private LocalDateTime generatedAt;
    // Participants with a best completed attempt that is fully graded: the base of every figure
    private int participantCount;
    // Completed sessions that still wait for a grader (not in the figures)
    private int pendingGradingSessionCount;
    private List<Question> questions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Question {
        private Long questionId;
        private Integer orderIndex;
        // At most 200 characters
        private String text;
        private QuestionType type;
        private BigDecimal maxPoints;
        // Participants who gave a non-empty answer / who did not
        private int answeredCount;
        private int unansweredCount;
        // Full points
        private int correctCount;
        // More than zero but less than the full points: only possible for manually graded answers
        // (choice questions are all-or-nothing)
        private int partiallyCorrectCount;
        // correctCount / answeredCount; absent when nobody answered
        private BigDecimal correctRate;
        // Points earned / points possible, over all participants (an unanswered question earns zero)
        private BigDecimal averageScoreRate;
        // EASY (correctRate >= 0.8), MEDIUM (>= 0.5), HARD, or INSUFFICIENT_DATA with fewer than 5
        // participants or when nobody answered
        private String difficulty;
        // Correct share of the top 27 % of participants minus that of the bottom 27 % (by total
        // percentage); absent with fewer than 10 participants
        private BigDecimal discriminationIndex;

        // Choice questions
        private List<Option> options;

        // Open-ended questions
        private Integer gradedCount;
        // Answers of completed sessions that still wait for a grader (those sessions are not in the figures above)
        private Integer pendingGradingCount;
        // Average of points / maximum * 100 over the graded answers
        private BigDecimal averageGradePercent;

        // Fill-in-the-blank questions: the 10 most frequent answers (trimmed, lower case)
        private List<TextAnswer> topAnswers;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Option {
        private Long optionId;
        // At most 200 characters
        private String text;
        @JsonProperty("isCorrect")
        private boolean correct;
        private int selectedCount;
        // selectedCount / participants who answered the question
        private BigDecimal selectedRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TextAnswer {
        private String text;
        private int count;
        @JsonProperty("isCorrect")
        private boolean correct;
    }
}
