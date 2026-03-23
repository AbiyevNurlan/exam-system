package com.examportal.common.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Question Entity
 * Represents a question in an exam.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("questions")
public class Question {

    @Id
    private Long id;

    @Column("exam_id")
    private Long examId;

    @Column("question_text")
    private String questionText;

    @Column("question_type")
    private QuestionType questionType;

    @Column("points")
    private BigDecimal points;

    @Column("order_index")
    private Integer orderIndex;

    @Column("allow_multiple_answers")
    private Boolean allowMultipleAnswers;

    @Column("is_required")
    private Boolean isRequired;

    @Column("explanation")
    private String explanation;

    @Column("active")
    private Boolean active;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;

    @Version
    private Long version;
}
