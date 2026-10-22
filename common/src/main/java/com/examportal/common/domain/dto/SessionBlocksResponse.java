package com.examportal.common.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Where a session is among the blocks of its exam (B12b), for the participant's screen. Only for exams with more
 * than one block, block timers or a block without free navigation; other sessions have none.
 *
 * phase: BLOCK (a block is being taken), BREAK (the pause after block current), DONE (the last block is over).
 * sequential: one block at a time, a block that was left stays closed; otherwise every block is open.
 * questions: the questions the participant may see now (the current block, or every block when open), in the
 * session's order; never a later block's, never during a break. No answer key.
 * cursor: a block without free navigation, the index (in its questions) of the furthest question reached; the ones
 * before it are closed.
 * serverTime lets the screen count down with the server's clock.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionBlocksResponse {

    private String sessionId;
    private String phase;
    private Boolean sequential;
    private String timingMode;
    private Integer current;
    private LocalDateTime serverTime;
    private LocalDateTime sessionEndTime;
    private LocalDateTime blockDeadline;
    private LocalDateTime breakUntil;
    private Boolean canSkipBreak;
    private Integer cursor;
    private List<Block> blocks;
    private List<QuestionResponse> questions;

    /**
     * status: DONE (left), CURRENT, UPCOMING, OPEN (an open session: every block)
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Block {
        private Long blockId;
        private Integer position;
        private String title;
        private String instructions;
        private Integer questionCount;
        private Integer timeLimitMinutes;
        private Integer breakAfterMinutes;
        private Boolean freeNavigation;
        private String status;
        private LocalDateTime startedAt;
        private LocalDateTime deadline;
        private LocalDateTime finishedAt;
        // A block without free navigation: the furthest question reached (index in its questions)
        private Integer cursor;
    }
}
