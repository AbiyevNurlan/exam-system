package com.examportal.exam.validator;

import com.examportal.common.domain.dto.CreateExamRequest;
import com.examportal.common.domain.dto.ExamBlockDTO;
import com.examportal.common.domain.dto.OptionDTO;
import com.examportal.common.domain.dto.QuestionDTO;
import com.examportal.common.domain.dto.UpdateExamRequest;
import com.examportal.common.domain.entity.TimingMode;
import com.examportal.common.exception.ValidationException;
import com.examportal.exam.exception.ExamException;
import com.examportal.exam.structure.ExamState;
import com.examportal.exam.structure.StructurePlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Exam Validator
 * Validates exam creation and update requests.
 */
@Slf4j
@Component
public class ExamValidator {

    private static final int MAX_BLOCKS = 20;
    private static final int MAX_QUESTIONS = 200;

    /**
     * Validate create exam request
     */
    public Mono<Void> validateCreateRequest(CreateExamRequest request) {
        TimingMode mode = request.getTimingMode() != null ? request.getTimingMode() : TimingMode.EXAM_TIMER;
        return validateRequest(request.getStartTime(), request.getEndTime(), request.getDurationMinutes(), mode,
                request.getQuestions(), request.getBlocks());
    }

    /**
     * Validate update exam request. mode: the timing mode the exam will have (the request's, or the stored one).
     */
    public Mono<Void> validateUpdateRequest(UpdateExamRequest request, TimingMode mode) {
        return validateRequest(request.getStartTime(), request.getEndTime(), request.getDurationMinutes(),
                mode != null ? mode : TimingMode.EXAM_TIMER, request.getQuestions(), request.getBlocks());
    }

    /**
     * B12a: the timing of the blocks as they will be stored. With BLOCK_TIMERS every block needs a time limit and
     * the limits plus the breaks between blocks must fit the window (400 EXAM_DURATION_EXCEEDS_WINDOW); that total
     * becomes the exam's duration. With EXAM_TIMER the duration stays as given (checked by validate*Request).
     * Emits the duration to store.
     */
    public Mono<Integer> validateTiming(TimingMode mode, Integer durationMinutes, LocalDateTime start, LocalDateTime end,
                                        StructurePlan plan) {
        return Mono.defer(() -> {
            if (mode != TimingMode.BLOCK_TIMERS) {
                return Mono.just(durationMinutes);
            }
            for (int i = 0; i < plan.blocks().size(); i++) {
                if (plan.blocks().get(i).timeLimitMinutes() == null) {
                    return Mono.error(new ValidationException("blocks[" + i + "].timeLimitMinutes",
                            "Block " + (i + 1) + ": a time limit is required with BLOCK_TIMERS"));
                }
            }
            int total = ExamState.totalMinutes(mode, durationMinutes, plan.blocks().stream()
                    .map(block -> new int[]{block.timeLimitMinutes(), block.breakAfterMinutes()})
                    .toList());
            long window = Duration.between(start, end).toMinutes();
            if (total > window) {
                return Mono.error(ExamException.durationExceedsWindow(total, window));
            }
            return Mono.just(total);
        });
    }

    private Mono<Void> validateRequest(LocalDateTime start, LocalDateTime end, Integer durationMinutes, TimingMode mode,
                                       List<QuestionDTO> questions, List<ExamBlockDTO> blocks) {
        return Mono.defer(() -> {
            // Validate time range
            if (!end.isAfter(start)) {
                return Mono.error(ExamException.invalidTimeRange());
            }

            // Validate duration matches time range (with block timers the duration is computed: validateTiming)
            long actualDuration = Duration.between(start, end).toMinutes();
            if (mode != TimingMode.BLOCK_TIMERS && durationMinutes > actualDuration) {
                return Mono.error(new ValidationException(
                        "Duration (" + durationMinutes + " min) exceeds time window (" + actualDuration + " min)"
                ));
            }

            boolean hasBlocks = blocks != null && !blocks.isEmpty();
            boolean hasQuestions = questions != null && !questions.isEmpty();
            if (hasBlocks && hasQuestions) {
                return Mono.error(new ValidationException("Send the questions either inside blocks or as questions, not both"));
            }
            if (hasBlocks) {
                if (blocks.size() > MAX_BLOCKS) {
                    return Mono.error(new ValidationException("blocks", "An exam has at most " + MAX_BLOCKS + " blocks"));
                }
                for (int i = 0; i < blocks.size(); i++) {
                    Integer position = blocks.get(i).getPosition();
                    if (position != null && position != i + 1) {
                        return Mono.error(new ValidationException("blocks[" + i + "].position",
                                "Block positions must run 1.." + blocks.size() + " in the order of the blocks"));
                    }
                }
            }
            List<QuestionDTO> all = hasBlocks
                    ? blocks.stream().flatMap(block -> block.getQuestions() == null ? java.util.stream.Stream.<QuestionDTO>empty()
                            : block.getQuestions().stream()).toList()
                    : questions;
            if (all != null && all.size() > MAX_QUESTIONS) {
                return Mono.error(new ValidationException("An exam cannot have more than " + MAX_QUESTIONS + " questions"));
            }

            // Validate questions (numbered over all blocks, as participants see them)
            return validateQuestions(all);
        });
    }

    /**
     * Validate list of questions
     */
    private Mono<Void> validateQuestions(List<QuestionDTO> questions) {
        if (questions == null || questions.isEmpty()) {
            return Mono.error(new ValidationException("At least one question is required"));
        }

        for (int i = 0; i < questions.size(); i++) {
            QuestionDTO question = questions.get(i);
            try {
                validateQuestion(question, i + 1);
            } catch (ValidationException e) {
                return Mono.error(e);
            }
        }

        return Mono.empty();
    }

    /**
     * Validate a single question
     */
    private void validateQuestion(QuestionDTO question, int questionNumber) {
        if (question.getQuestionText() == null || question.getQuestionText().isBlank()) {
            throw new ValidationException("Question " + questionNumber + ": Question text is required");
        }

        if (question.getQuestionType() == null) {
            throw new ValidationException("Question " + questionNumber + ": Question type is required");
        }

        // Validate options based on question type
        switch (question.getQuestionType()) {
            case MULTIPLE_CHOICE -> validateMultipleChoiceQuestion(question, questionNumber);
            case TRUE_FALSE -> validateTrueFalseQuestion(question, questionNumber);
            case ORDERING -> validateItems(question, questionNumber, false);
            case MATCHING -> validateItems(question, questionNumber, true);
            case FILL_BLANK, OPEN_ENDED, SHORT_ANSWER -> {
                // These types may not require options
            }
        }
    }

    /**
     * Validate multiple choice question
     */
    private void validateMultipleChoiceQuestion(QuestionDTO question, int questionNumber) {
        List<OptionDTO> options = question.getOptions();
        
        if (options == null || options.size() < 2) {
            throw new ValidationException("Question " + questionNumber + ": Multiple choice questions must have at least 2 options");
        }

        long correctCount = options.stream()
                .filter(opt -> Boolean.TRUE.equals(opt.getIsCorrect()))
                .count();

        if (correctCount == 0) {
            throw new ValidationException("Question " + questionNumber + ": At least one correct option is required");
        }

        if (!Boolean.TRUE.equals(question.getAllowMultipleAnswers()) && correctCount > 1) {
            throw new ValidationException("Question " + questionNumber + ": Single answer questions can only have one correct option");
        }

        // Validate option texts
        for (int i = 0; i < options.size(); i++) {
            OptionDTO option = options.get(i);
            if (option.getOptionText() == null || option.getOptionText().isBlank()) {
                throw new ValidationException("Question " + questionNumber + ", Option " + (i + 1) + ": Option text is required");
            }
        }
    }

    /**
     * ORDERING: 2..10 items, each with a text (their order is the answer). MATCHING: 2..10 pairs, each with both sides.
     */
    private void validateItems(QuestionDTO question, int questionNumber, boolean pairs) {
        List<OptionDTO> options = question.getOptions();
        String what = pairs ? "pairs" : "items";
        if (options == null || options.size() < 2 || options.size() > 10) {
            throw new ValidationException("Question " + questionNumber + ": " + (pairs ? "Matching" : "Ordering")
                    + " questions must have 2 to 10 " + what);
        }
        for (int i = 0; i < options.size(); i++) {
            OptionDTO option = options.get(i);
            if (option.getOptionText() == null || option.getOptionText().isBlank()
                    || (pairs && (option.getMatchText() == null || option.getMatchText().isBlank()))) {
                throw new ValidationException("Question " + questionNumber + ", " + (pairs ? "Pair " : "Item ") + (i + 1)
                        + ": " + (pairs ? "Both sides are required" : "Item text is required"));
            }
        }
    }

    /**
     * Validate true/false question
     */
    private void validateTrueFalseQuestion(QuestionDTO question, int questionNumber) {
        List<OptionDTO> options = question.getOptions();

        if (options == null || options.size() != 2) {
            throw new ValidationException("Question " + questionNumber + ": True/False questions must have exactly 2 options");
        }

        long correctCount = options.stream()
                .filter(opt -> Boolean.TRUE.equals(opt.getIsCorrect()))
                .count();

        if (correctCount != 1) {
            throw new ValidationException("Question " + questionNumber + ": True/False questions must have exactly 1 correct option");
        }
    }
}
