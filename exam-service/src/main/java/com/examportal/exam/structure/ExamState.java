package com.examportal.exam.structure;

import com.examportal.common.domain.entity.ExamAccessType;
import com.examportal.common.domain.entity.ExamStatus;
import com.examportal.common.domain.entity.QuestionType;
import com.examportal.common.domain.entity.TimingMode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Everything an exam is made of at one moment (B12a): its settings, its blocks in order and their
 * questions with options and media, as stored. Read inside the transaction of a change, before and
 * after it, so the history can say what changed; also the source of a published snapshot, of the
 * readiness check and of a clone. Immutable.
 */
public record ExamState(Long id, UUID createdBy, String title, String description, ExamStatus status,
                        Integer durationMinutes, LocalDateTime startTime, LocalDateTime endTime,
                        BigDecimal passingScore, Boolean shuffleQuestions, Boolean shuffleOptions,
                        Boolean showResultsImmediately, Integer maxAttempts, Integer maxAttemptsPerDay,
                        Long categoryId, ExamAccessType accessType, TimingMode timingMode,
                        boolean allowReturnToPreviousBlocks, boolean allowSkipBreak, boolean showBlockBreakdown,
                        List<Long> audienceGroupIds, List<BlockState> blocks) {

    /**
     * Every question of the exam in the order participants number them: blocks by position, inside a
     * block by order. questionNumber = index + 1.
     */
    public List<QuestionState> questions() {
        return blocks.stream().flatMap(block -> block.questions().stream()).toList();
    }

    /**
     * Minutes the exam takes: the exam timer, or with block timers every block's limit plus the breaks
     * between blocks (the break after the last block does not count)
     */
    public int totalMinutes() {
        return totalMinutes(timingMode, durationMinutes, blocks.stream()
                .map(block -> new int[]{block.timeLimitMinutes() == null ? 0 : block.timeLimitMinutes(), block.breakAfterMinutes()})
                .toList());
    }

    /**
     * The same rule for blocks that are not stored yet: each entry is {timeLimit, breakAfter}
     */
    public static int totalMinutes(TimingMode mode, Integer durationMinutes, List<int[]> blocks) {
        if (mode != TimingMode.BLOCK_TIMERS) {
            return durationMinutes == null ? 0 : durationMinutes;
        }
        int total = 0;
        for (int i = 0; i < blocks.size(); i++) {
            total += blocks.get(i)[0];
            if (i < blocks.size() - 1) {
                total += blocks.get(i)[1];
            }
        }
        return total;
    }

    /**
     * Whether the participant side of blocks (B12b) is needed to take this exam as configured: block
     * timers, a block that forbids free navigation, or returning to earlier blocks
     */
    public boolean needsBlockRules() {
        return timingMode == TimingMode.BLOCK_TIMERS
                || allowReturnToPreviousBlocks
                || blocks.stream().anyMatch(block -> !block.freeNavigation());
    }

    public record BlockState(Long id, int position, String title, String instructions, Integer timeLimitMinutes,
                             int breakAfterMinutes, boolean freeNavigation, boolean shuffleQuestions,
                             boolean showResultToParticipant, List<QuestionState> questions) {
    }

    public record QuestionState(Long id, Long blockId, int orderIndex, String questionText, QuestionType questionType,
                                BigDecimal points, boolean allowMultipleAnswers, boolean required, String explanation,
                                List<MediaRef> media, List<OptionState> options, ScaleState scale) {

        // Every question type but LINEAR_SCALE has no scale
        public QuestionState(Long id, Long blockId, int orderIndex, String questionText, QuestionType questionType,
                             BigDecimal points, boolean allowMultipleAnswers, boolean required, String explanation,
                             List<MediaRef> media, List<OptionState> options) {
            this(id, blockId, orderIndex, questionText, questionType, points, allowMultipleAnswers, required, explanation,
                    media, options, null);
        }
    }

    /**
     * The scale of a LINEAR_SCALE question (exam V22); null for every other type
     */
    public record ScaleState(int min, int max, String minLabel, String maxLabel) {

        // What the editor sent, with blank labels as null; null when the request has no complete scale
        public static ScaleState of(com.examportal.common.domain.dto.ScaleSettings settings) {
            if (settings == null || settings.getMin() == null || settings.getMax() == null) {
                return null;
            }
            return new ScaleState(settings.getMin(), settings.getMax(), blankToNull(settings.getMinLabel()),
                    blankToNull(settings.getMaxLabel()));
        }

        public com.examportal.common.domain.dto.ScaleSettings toSettings() {
            return com.examportal.common.domain.dto.ScaleSettings.builder()
                    .min(min).max(max).minLabel(minLabel).maxLabel(maxLabel).build();
        }

        private static String blankToNull(String text) {
            return text == null || text.isBlank() ? null : text.strip();
        }
    }

    public record OptionState(Long id, int orderIndex, String optionText, String matchText, boolean correct, List<MediaRef> media) {
    }

    /**
     * A media link; status is that of the media asset (ACTIVE, or PENDING/DELETED when it went missing)
     */
    public record MediaRef(UUID mediaId, int position, Integer maxPlays, String status) {
    }
}
