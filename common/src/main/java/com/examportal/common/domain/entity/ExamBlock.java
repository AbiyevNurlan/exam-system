package com.examportal.common.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * One block of an exam (B12a, exam_blocks): a titled part with its own questions, optional time
 * limit and a break after it. Every exam has 1..20 blocks, position 1..n without gaps.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("exam_blocks")
public class ExamBlock {

    @Id
    private Long id;

    @Column("exam_id")
    private Long examId;

    @Column("position")
    private Integer position;

    @Column("title")
    private String title;

    @Column("instructions")
    private String instructions;

    // null = no own limit (EXAM_TIMER); required and > 0 in BLOCK_TIMERS mode
    @Column("time_limit_minutes")
    private Integer timeLimitMinutes;

    @Column("break_after_minutes")
    private Integer breakAfterMinutes;

    @Column("free_navigation")
    private Boolean freeNavigation;

    @Column("shuffle_questions")
    private Boolean shuffleQuestions;

    // false: the participant never gets this block's score, percentage or answers (B12a, server-side)
    @Column("show_result_to_participant")
    private Boolean showResultToParticipant;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;
}
