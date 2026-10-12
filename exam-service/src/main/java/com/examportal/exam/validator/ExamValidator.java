package com.examportal.exam.validator;

import com.examportal.common.domain.dto.CreateExamRequest;
import com.examportal.common.domain.dto.OptionDTO;
import com.examportal.common.domain.dto.QuestionDTO;
import com.examportal.common.domain.dto.UpdateExamRequest;
import com.examportal.common.exception.ValidationException;
import com.examportal.exam.exception.ExamException;
import com.examportal.exam.repository.ExamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Exam Validator
 * Validates exam creation and update requests.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExamValidator {

    private final ExamRepository examRepository;

    /**
     * Validate create exam request
     */
    public Mono<Void> validateCreateRequest(CreateExamRequest request) {
        return Mono.defer(() -> {
            // Validate time range
            if (!request.getEndTime().isAfter(request.getStartTime())) {
                return Mono.error(ExamException.invalidTimeRange());
            }

            // Validate duration matches time range
            long actualDuration = Duration.between(request.getStartTime(), request.getEndTime()).toMinutes();
            if (request.getDurationMinutes() > actualDuration) {
                return Mono.error(new ValidationException(
                        "Duration (" + request.getDurationMinutes() + " min) exceeds time window (" + actualDuration + " min)"
                ));
            }

            // Validate questions
            return validateQuestions(request.getQuestions());
        });
    }

    /**
     * Validate update exam request
     */
    public Mono<Void> validateUpdateRequest(UpdateExamRequest request) {
        return Mono.defer(() -> {
            // Validate time range
            if (!request.getEndTime().isAfter(request.getStartTime())) {
                return Mono.error(ExamException.invalidTimeRange());
            }

            // Validate duration matches time range
            long actualDuration = Duration.between(request.getStartTime(), request.getEndTime()).toMinutes();
            if (request.getDurationMinutes() > actualDuration) {
                return Mono.error(new ValidationException(
                        "Duration (" + request.getDurationMinutes() + " min) exceeds time window (" + actualDuration + " min)"
                ));
            }

            // Validate questions
            return validateQuestions(request.getQuestions());
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
            case FILL_BLANK, OPEN_ENDED -> {
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

    /**
     * Check for scheduling conflicts
     */
    public Mono<Void> validateNoSchedulingConflict(UUID userId, LocalDateTime startTime, 
                                                    LocalDateTime endTime, Long excludeExamId) {
        return examRepository.findOverlappingExams(userId, startTime, endTime, excludeExamId)
                .hasElements()
                .flatMap(hasConflict -> {
                    if (hasConflict) {
                        return Mono.error(ExamException.schedulingConflict());
                    }
                    return Mono.empty();
                });
    }
}
