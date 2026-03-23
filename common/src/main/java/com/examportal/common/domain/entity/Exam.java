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

    @Column("category_id")
    private Long categoryId;

    @Column("created_by")
    private Long createdBy;

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
