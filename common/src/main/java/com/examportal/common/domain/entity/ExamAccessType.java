package com.examportal.common.domain.entity;

/**
 * Who may see and take an exam
 */
public enum ExamAccessType {
    /** Every authenticated user */
    PUBLIC,
    /** Only members of the exam's audience groups */
    GROUP
}