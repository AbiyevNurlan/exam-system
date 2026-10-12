package com.examportal.common.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * User Answer Entity
 * Represents a user's answer to a question.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("user_answers")
public class UserAnswer {

    @Id
    private Long id;

    @Column("session_id")
    private Long sessionId;

    @Column("question_id")
    private Long questionId;

    @Column("selected_option_ids")
    private Long[] selectedOptionIds;  // Chosen options; one element for single choice and true/false

    @Column("text_answer")
    private String textAnswer;

    @Column("is_correct")
    private Boolean isCorrect;

    @Column("points_earned")
    private BigDecimal pointsEarned;

    @Column("needs_manual_review")
    private Boolean needsManualReview;

    @Column("answered_at")
    private LocalDateTime answeredAt;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;

    // Alias setter for isCorrect
    public void setCorrect(boolean correct) {
        this.isCorrect = correct;
    }
}
