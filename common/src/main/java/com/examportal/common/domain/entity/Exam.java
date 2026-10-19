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
import java.util.UUID;

/**
 * Exam Entity
 * Represents an examination with its configuration and metadata.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("exams")
public class Exam {

    @Id
    private Long id;

    @Column("title")
    private String title;

    @Column("description")
    private String description;

    @Column("duration_minutes")
    private Integer durationMinutes;

    @Column("start_time")
    private LocalDateTime startTime;

    @Column("end_time")
    private LocalDateTime endTime;

    @Column("status")
    private ExamStatus status;

    @Column("total_questions")
    private Integer totalQuestions;

    @Column("passing_score")
    private BigDecimal passingScore;

    @Column("shuffle_questions")
    private Boolean shuffleQuestions;

    @Column("shuffle_options")
    private Boolean shuffleOptions;

    @Column("show_results_immediately")
    private Boolean showResultsImmediately;

    @Column("max_attempts")
    private Integer maxAttempts;

    // Sessions one user may open per calendar day; null = no daily limit
    @Column("max_attempts_per_day")
    private Integer maxAttemptsPerDay;

    @Column("category_id")
    private Long categoryId;

    @Column("created_by")
    private UUID createdBy;

    @Column("access_type")
    private ExamAccessType accessType;

    @Column("active")
    private Boolean active;

    // B12a: blocks (exam_blocks). Defaults are the behaviour before blocks.
    @Column("timing_mode")
    private TimingMode timingMode;

    @Column("allow_return_to_previous_blocks")
    private Boolean allowReturnToPreviousBlocks;

    @Column("allow_skip_break")
    private Boolean allowSkipBreak;

    // false: participants never get a result per block, whatever the blocks say
    @Column("show_block_breakdown")
    private Boolean showBlockBreakdown;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    /**
     * Check if the exam is currently active based on time window
     */
    public boolean isWithinTimeWindow() {
        LocalDateTime now = LocalDateTime.now();
        return now.isAfter(startTime) && now.isBefore(endTime);
    }

    /**
     * Check if the exam can be started
     */
    public boolean canStart() {
        return status == ExamStatus.ACTIVE && isWithinTimeWindow();
    }

    /**
     * Check if the exam can be modified
     */
    public boolean canModify() {
        return status == ExamStatus.DRAFT || status == ExamStatus.SCHEDULED;
    }
}
