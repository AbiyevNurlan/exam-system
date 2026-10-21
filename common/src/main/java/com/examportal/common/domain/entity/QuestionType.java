package com.examportal.common.domain.entity;

/**
 * Question Type Enumeration
 * Defines the types of questions supported in exams.
 */
public enum QuestionType {
    MULTIPLE_CHOICE,
    TRUE_FALSE,
    FILL_BLANK,
    OPEN_ENDED,
    // Items to be put in order: the options in their order_index are the right order (2..10 items)
    ORDERING,
    // Pairs: option_text on the left, match_text the right side that belongs to it (2..10 pairs)
    MATCHING,
    // A short typed answer, compared like FILL_BLANK with the accepted answers (the options)
    SHORT_ANSWER
}
