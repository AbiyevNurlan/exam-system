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
 * Exam Audience Entity
 * One group whose members may take a GROUP exam.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("exam_audience")
public class ExamAudience {

    @Id
    private Long id;

    @Column("exam_id")
    private Long examId;

    @Column("group_id")
    private Long groupId;

    @Column("created_at")
    private LocalDateTime createdAt;
}