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

import java.time.LocalDateTime;

/**
 * Question Option Entity
 * Represents an answer option for a question.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("question_options")
public class QuestionOption {

    @Id
    private Long id;

    @Column("question_id")
    private Long questionId;

    @Column("option_text")
    private String optionText;

    // MATCHING: the right side of the pair (exam V21); null for every other type
    @Column("match_text")
    private String matchText;

    @Column("is_correct")
    private Boolean isCorrect;

    @Column("order_index")
    private Integer orderIndex;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;
}
