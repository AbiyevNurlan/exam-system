package com.examportal.exam.service;

import com.examportal.common.domain.dto.ExamReadinessResponse;
import com.examportal.common.domain.dto.ExamReadinessResponse.Item;
import com.examportal.common.domain.entity.ExamAccessType;
import com.examportal.common.domain.entity.QuestionType;
import com.examportal.common.domain.entity.TimingMode;
import com.examportal.exam.structure.ExamState;
import com.examportal.exam.structure.ExamState.BlockState;
import com.examportal.exam.structure.ExamState.MediaRef;
import com.examportal.exam.structure.ExamState.OptionState;
import com.examportal.exam.structure.ExamState.QuestionState;
import com.examportal.exam.structure.ExamStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Whether an exam can be published (B12a). One check for GET /readiness and for publishing.
 *
 * Blocking: no question, an empty block, a multiple choice question with fewer than 2 options or no correct
 * one, a true/false question without exactly 2 options and 1 correct, a fill-in-the-blank question without an
 * expected answer, a question worth 0 points, a media that is no longer there, block timers without a limit,
 * a duration that does not fit the window, a window that has ended, and before B12b block timers, fixed
 * navigation or returning to earlier blocks (FEATURE_NOT_AVAILABLE).
 * Warnings: a GROUP exam without an active audience group (nobody could take it), a start in the past (it opens
 * at once), block time limits that the exam timer ignores.
 */
@Service
@RequiredArgsConstructor
public class ExamReadinessService {

    private final ExamStateRepository states;
    private final DatabaseClient databaseClient;
    private final ExamFeatures features;

    public Mono<ExamReadinessResponse> check(Long examId) {
        return states.load(examId)
                .flatMap(state -> activeAudienceGroups(examId)
                        .map(groups -> evaluate(state, groups, LocalDateTime.now(), features.blockRulesEnabled())));
    }

    /**
     * For the ADMIN or the owning INSTRUCTOR (checked by the caller)
     */
    public Mono<ExamReadinessResponse> check(Long examId, UUID userId, String role, ExamAccessPolicy accessPolicy) {
        return accessPolicy.findManageableExam(examId, userId, role).then(check(examId));
    }

    private Mono<Integer> activeAudienceGroups(Long examId) {
        return databaseClient.sql("""
                        SELECT COUNT(*) FROM exam_audience a JOIN user_groups g ON g.id = a.group_id AND g.active = true
                        WHERE a.exam_id = :examId
                        """)
                .bind("examId", examId)
                .map(row -> row.get(0, Long.class).intValue())
                .one();
    }

    public static ExamReadinessResponse evaluate(ExamState state, int activeAudienceGroups, LocalDateTime now,
                                                 boolean blockRulesEnabled) {
        List<Item> blocking = new ArrayList<>();
        List<Item> warnings = new ArrayList<>();
        int total = state.totalMinutes();
        long window = state.startTime() != null && state.endTime() != null
                ? Duration.between(state.startTime(), state.endTime()).toMinutes() : 0;

        if (state.questions().isEmpty()) {
            blocking.add(item("NO_QUESTIONS", null, null, null, null, null));
        }
        int number = 0;
        for (int b = 0; b < state.blocks().size(); b++) {
            BlockState block = state.blocks().get(b);
            int blockNumber = b + 1;
            if (block.questions().isEmpty() && !state.questions().isEmpty()) {
                blocking.add(item("EMPTY_BLOCK", block.id(), blockNumber, null, null, Map.of("blockTitle", block.title())));
            }
            if (state.timingMode() == TimingMode.BLOCK_TIMERS && block.timeLimitMinutes() == null) {
                blocking.add(item("BLOCK_TIME_LIMIT_MISSING", block.id(), blockNumber, null, null, Map.of("blockTitle", block.title())));
            }
            if (state.timingMode() != TimingMode.BLOCK_TIMERS && block.timeLimitMinutes() != null) {
                warnings.add(item("BLOCK_TIME_LIMIT_IGNORED", block.id(), blockNumber, null, null, Map.of("blockTitle", block.title())));
            }
            for (QuestionState question : block.questions()) {
                number++;
                questionProblems(question, block, blockNumber, number, blocking);
            }
        }
        if (state.endTime() != null && !state.endTime().isAfter(now)) {
            blocking.add(item("WINDOW_ENDED", null, null, null, null, Map.of("endTime", state.endTime().toString())));
        } else if (state.startTime() != null && state.startTime().isBefore(now)) {
            warnings.add(item("START_IN_PAST", null, null, null, null, Map.of("startTime", state.startTime().toString())));
        }
        if (total > window) {
            blocking.add(item("DURATION_EXCEEDS_WINDOW", null, null, null, null, Map.of("totalMinutes", total, "windowMinutes", window)));
        }
        if (!blockRulesEnabled && state.needsBlockRules()) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("timingMode", state.timingMode().name());
            params.put("allowReturnToPreviousBlocks", state.allowReturnToPreviousBlocks());
            params.put("blocksWithoutFreeNavigation", state.blocks().stream().filter(block -> !block.freeNavigation())
                    .map(BlockState::id).toList());
            blocking.add(item("FEATURE_NOT_AVAILABLE", null, null, null, null, params));
        }
        if (state.accessType() == ExamAccessType.GROUP && activeAudienceGroups == 0) {
            warnings.add(item("AUDIENCE_EMPTY", null, null, null, null, null));
        }
        return ExamReadinessResponse.builder()
                .examId(state.id())
                .ready(blocking.isEmpty())
                .blocking(blocking)
                .warnings(warnings)
                .totalMinutes(total)
                .windowMinutes(window)
                .build();
    }

    // Package-private for the tests (ExamReadinessTestAccess)
    static void questionProblems(QuestionState question, BlockState block, int blockNumber, int number, List<Item> blocking) {
        List<OptionState> options = question.options();
        long correct = options.stream().filter(OptionState::correct).count();
        String code = null;
        if (question.questionType() == QuestionType.MULTIPLE_CHOICE) {
            if (options.size() < 2) {
                code = "MULTIPLE_CHOICE_INCOMPLETE";
            } else if (correct == 0) {
                code = "NO_CORRECT_ANSWER";
            }
        } else if (question.questionType() == QuestionType.TRUE_FALSE) {
            if (options.size() != 2 || correct != 1) {
                code = "TRUE_FALSE_INCOMPLETE";
            }
        } else if (question.questionType() == QuestionType.FILL_BLANK) {
            if (options.stream().noneMatch(option -> option.optionText() != null && !option.optionText().isBlank())) {
                code = "FILL_BLANK_INCOMPLETE";
            }
        } else if (question.questionType() == QuestionType.SHORT_ANSWER) {
            if (options.stream().noneMatch(option -> option.optionText() != null && !option.optionText().isBlank())) {
                code = "SHORT_ANSWER_INCOMPLETE";
            }
        } else if (question.questionType() == QuestionType.ORDERING) {
            if (options.size() < 2 || options.size() > 10 || options.stream().anyMatch(option -> option.optionText() == null || option.optionText().isBlank())) {
                code = "ORDERING_INCOMPLETE";
            }
        } else if (question.questionType() == QuestionType.MATCHING) {
            if (options.size() < 2 || options.size() > 10 || options.stream().anyMatch(option -> option.optionText() == null || option.optionText().isBlank()
                    || option.matchText() == null || option.matchText().isBlank())) {
                code = "MATCHING_INCOMPLETE";
            }
        } else if (question.questionType() == QuestionType.LINEAR_SCALE) {
            // A scale and exactly one right number on it (ExamValidator refuses anything else; this also covers rows
            // written before a check existed)
            Integer value = options.size() == 1
                    ? com.examportal.exam.validator.ExamValidator.scaleValue(options.get(0).optionText()) : null;
            if (question.scale() == null || value == null || value < question.scale().min() || value > question.scale().max()) {
                code = "LINEAR_SCALE_INCOMPLETE";
            }
        }
        if (code != null) {
            blocking.add(item(code, block.id(), blockNumber, question.id(), number, null));
        }
        if (question.points() == null || question.points().compareTo(BigDecimal.ZERO) <= 0) {
            blocking.add(item("ZERO_POINTS", block.id(), blockNumber, question.id(), number, null));
        }
        List<MediaRef> media = new ArrayList<>(question.media());
        options.forEach(option -> media.addAll(option.media()));
        for (MediaRef ref : media) {
            if (!"ACTIVE".equals(ref.status())) {
                blocking.add(item("MEDIA_MISSING", block.id(), blockNumber, question.id(), number,
                        Map.of("mediaId", ref.mediaId().toString(), "status", String.valueOf(ref.status()))));
            }
        }
    }

    private static Item item(String code, Long blockId, Integer blockNumber, Long questionId, Integer questionNumber,
                             Map<String, Object> params) {
        return Item.builder()
                .code(code)
                .messageKey("exam.readiness." + code)
                .blockId(blockId)
                .blockNumber(blockNumber)
                .questionId(questionId)
                .questionNumber(questionNumber)
                .params(params)
                .build();
    }
}
