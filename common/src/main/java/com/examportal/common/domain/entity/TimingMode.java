package com.examportal.common.domain.entity;

/**
 * How an exam is timed (B12a). EXAM_TIMER: one timer for the whole exam (durationMinutes), as before.
 * BLOCK_TIMERS: every block has its own time limit, with breaks in between; the total is computed.
 * The participant side of BLOCK_TIMERS is B12b.
 */
public enum TimingMode {
    EXAM_TIMER,
    BLOCK_TIMERS
}
