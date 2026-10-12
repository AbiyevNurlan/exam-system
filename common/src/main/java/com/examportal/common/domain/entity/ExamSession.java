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
 * Exam Session Entity
 * Represents a user's exam attempt session.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("exam_sessions")
public class ExamSession {

    @Id
    private Long id;

    @Column("session_id")
    private String sessionId;

    @Column("exam_id")
    private Long examId;

    @Column("user_id")
    private UUID userId;

    @Column("start_time")
    private LocalDateTime startTime;

    @Column("end_time")
    private LocalDateTime endTime;

    @Column("status")
    private SessionStatus status;

    @Column("score")
    private BigDecimal score;

    @Column("total_points")
    private BigDecimal totalPoints;

    @Column("percentage")
    private BigDecimal percentage;

    @Column("passed")
    private Boolean passed;

    @Column("submitted_at")
    private LocalDateTime submittedAt;

    @Column("ip_address")
    private String ipAddress;

    @Column("browser_info")
    private String browserInfo;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    /**
     * Check if the session is still active
     */
    public boolean isActive() {
        return status == SessionStatus.IN_PROGRESS;
    }

    /**
     * Check if the session has expired
     */
    public boolean isExpired() {
        return endTime != null && LocalDateTime.now().isAfter(endTime);
    }

    // Alias methods for field access
    public LocalDateTime getCompletedAt() {
        return submittedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.submittedAt = completedAt;
    }

    public BigDecimal getTotalScore() {
        return score;
    }

    public void setTotalScore(BigDecimal totalScore) {
        this.score = totalScore;
    }

    public BigDecimal getPercentageScore() {
        return percentage;
    }

    public void setPercentageScore(BigDecimal percentageScore) {
        this.percentage = percentageScore;
    }
}
