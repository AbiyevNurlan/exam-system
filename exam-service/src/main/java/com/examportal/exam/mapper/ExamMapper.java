package com.examportal.exam.mapper;

import com.examportal.common.domain.dto.*;
import com.examportal.common.domain.entity.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Exam Mapper
 * Maps between entities and DTOs.
 */
@Component
public class ExamMapper {

    /**
     * Map CreateExamRequest to Exam entity
     */
    public Exam toEntity(CreateExamRequest request, UUID userId) {
        return Exam.builder()
                .title(request.getTitle())
                .description(request.getDescription())
                .durationMinutes(request.getDurationMinutes())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .status(ExamStatus.DRAFT)
                .totalQuestions(request.getQuestions() != null ? request.getQuestions().size() : 0)
                .passingScore(request.getPassingScore() != null ? request.getPassingScore() : BigDecimal.valueOf(60))
                .shuffleQuestions(request.getShuffleQuestions())
                .shuffleOptions(request.getShuffleOptions())
                .showResultsImmediately(request.getShowResultsImmediately())
                .maxAttempts(request.getMaxAttempts() != null ? request.getMaxAttempts() : 1)
                .categoryId(request.getCategoryId())
                .createdBy(userId)
                .active(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Update Exam entity from UpdateExamRequest
     */
    public void updateEntity(Exam exam, UpdateExamRequest request) {
        exam.setTitle(request.getTitle());
        exam.setDescription(request.getDescription());
        exam.setDurationMinutes(request.getDurationMinutes());
        exam.setStartTime(request.getStartTime());
        exam.setEndTime(request.getEndTime());
        exam.setTotalQuestions(request.getQuestions() != null ? request.getQuestions().size() : 0);
        exam.setPassingScore(request.getPassingScore());
        exam.setShuffleQuestions(request.getShuffleQuestions());
        exam.setShuffleOptions(request.getShuffleOptions());
        exam.setShowResultsImmediately(request.getShowResultsImmediately());
        exam.setMaxAttempts(request.getMaxAttempts());
        exam.setCategoryId(request.getCategoryId());
        exam.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * Map QuestionDTO to Question entity
     */
    public Question toEntity(QuestionDTO dto, Long examId) {
        return Question.builder()
                .examId(examId)
                .questionText(dto.getQuestionText())
                .questionType(dto.getQuestionType())
                .points(dto.getPoints() != null ? dto.getPoints() : BigDecimal.ONE)
                .orderIndex(dto.getOrderIndex())
                .allowMultipleAnswers(dto.getAllowMultipleAnswers())
                .isRequired(dto.getIsRequired() != null ? dto.getIsRequired() : true)
                .explanation(dto.getExplanation())
                .active(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Map OptionDTO to QuestionOption entity
     */
    public QuestionOption toEntity(OptionDTO dto, Long questionId) {
        return QuestionOption.builder()
                .questionId(questionId)
                .optionText(dto.getOptionText())
                .isCorrect(dto.getIsCorrect() != null ? dto.getIsCorrect() : false)
                .orderIndex(dto.getOrderIndex())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Map Exam entity to ExamResponse
     */
    public ExamResponse toResponse(Exam exam) {
        return ExamResponse.builder()
                .id(exam.getId())
                .title(exam.getTitle())
                .description(exam.getDescription())
                .durationMinutes(exam.getDurationMinutes())
                .startTime(exam.getStartTime())
                .endTime(exam.getEndTime())
                .status(exam.getStatus())
                .totalQuestions(exam.getTotalQuestions())
                .passingScore(exam.getPassingScore())
                .shuffleQuestions(exam.getShuffleQuestions())
                .shuffleOptions(exam.getShuffleOptions())
                .showResultsImmediately(exam.getShowResultsImmediately())
                .maxAttempts(exam.getMaxAttempts())
                .categoryId(exam.getCategoryId())
                .createdBy(exam.getCreatedBy())
                .createdAt(exam.getCreatedAt())
                .updatedAt(exam.getUpdatedAt())
                .canStart(exam.canStart())
                .build();
    }

    /**
     * Map Exam entity to ExamResponse with questions
     */
    public ExamResponse toResponse(Exam exam, List<QuestionResponse> questions) {
        ExamResponse response = toResponse(exam);
        response.setQuestions(questions);
        if (questions != null && !questions.isEmpty()) {
            BigDecimal totalPoints = questions.stream()
                    .map(QuestionResponse::getPoints)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            response.setTotalPoints(totalPoints);
        }
        return response;
    }

    /**
     * Map Question entity to QuestionResponse
     */
    public QuestionResponse toResponse(Question question) {
        return QuestionResponse.builder()
                .id(question.getId())
                .examId(question.getExamId())
                .questionText(question.getQuestionText())
                .questionType(question.getQuestionType())
                .points(question.getPoints())
                .orderIndex(question.getOrderIndex())
                .allowMultipleAnswers(question.getAllowMultipleAnswers())
                .isRequired(question.getIsRequired())
                .explanation(question.getExplanation())
                .build();
    }

    /**
     * Map Question entity to QuestionResponse with options
     */
    public QuestionResponse toResponse(Question question, List<OptionResponse> options) {
        QuestionResponse response = toResponse(question);
        response.setOptions(options);
        return response;
    }

    /**
     * Map Question entity to QuestionResponse for participants (hide explanation)
     */
    public QuestionResponse toParticipantResponse(Question question, List<OptionResponse> options) {
        return QuestionResponse.builder()
                .id(question.getId())
                .examId(question.getExamId())
                .questionText(question.getQuestionText())
                .questionType(question.getQuestionType())
                .points(question.getPoints())
                .orderIndex(question.getOrderIndex())
                .allowMultipleAnswers(question.getAllowMultipleAnswers())
                .isRequired(question.getIsRequired())
                .options(options)
                // explanation is hidden for participants during exam
                .build();
    }

    /**
     * Copy of a full ExamResponse without the answer key (isCorrect, explanation, open-ended answers).
     * The source is left untouched because it may be the cached instance.
     */
    public ExamResponse toParticipantView(ExamResponse full) {
        List<QuestionResponse> questions = full.getQuestions() == null ? null : full.getQuestions().stream()
                .map(q -> QuestionResponse.builder()
                        .id(q.getId())
                        .examId(q.getExamId())
                        .questionText(q.getQuestionText())
                        .questionType(q.getQuestionType())
                        .points(q.getPoints())
                        .orderIndex(q.getOrderIndex())
                        .allowMultipleAnswers(q.getAllowMultipleAnswers())
                        .isRequired(q.getIsRequired())
                        .options(participantOptions(q.getQuestionType(), q.getOptions()))
                        .build())
                .toList();

        return ExamResponse.builder()
                .id(full.getId())
                .title(full.getTitle())
                .description(full.getDescription())
                .durationMinutes(full.getDurationMinutes())
                .startTime(full.getStartTime())
                .endTime(full.getEndTime())
                .status(full.getStatus())
                .totalQuestions(full.getTotalQuestions())
                .passingScore(full.getPassingScore())
                .shuffleQuestions(full.getShuffleQuestions())
                .shuffleOptions(full.getShuffleOptions())
                .showResultsImmediately(full.getShowResultsImmediately())
                .maxAttempts(full.getMaxAttempts())
                .categoryId(full.getCategoryId())
                .categoryName(full.getCategoryName())
                .createdBy(full.getCreatedBy())
                .creatorUsername(full.getCreatorUsername())
                .createdAt(full.getCreatedAt())
                .updatedAt(full.getUpdatedAt())
                .questions(questions)
                .remainingAttempts(full.getRemainingAttempts())
                .canStart(full.getCanStart())
                .totalPoints(full.getTotalPoints())
                .build();
    }

    /**
     * Options a participant may see. For text questions the option text is the expected answer,
     * so those options are not sent at all.
     */
    public List<OptionResponse> participantOptions(QuestionType type, List<OptionResponse> options) {
        if (options == null || isTextQuestion(type)) {
            return null;
        }
        return options.stream()
                .map(o -> OptionResponse.builder()
                        .id(o.getId())
                        .questionId(o.getQuestionId())
                        .optionText(o.getOptionText())
                        .orderIndex(o.getOrderIndex())
                        .build())
                .toList();
    }

    public boolean isTextQuestion(QuestionType type) {
        return type == QuestionType.FILL_BLANK || type == QuestionType.OPEN_ENDED;
    }

    /**
     * Map QuestionOption entity to OptionResponse
     */
    public OptionResponse toResponse(QuestionOption option) {
        return OptionResponse.builder()
                .id(option.getId())
                .questionId(option.getQuestionId())
                .optionText(option.getOptionText())
                .orderIndex(option.getOrderIndex())
                .isCorrect(option.getIsCorrect())
                .build();
    }

    /**
     * Map QuestionOption entity to OptionResponse for participants (hide isCorrect)
     */
    public OptionResponse toParticipantResponse(QuestionOption option) {
        return OptionResponse.builder()
                .id(option.getId())
                .questionId(option.getQuestionId())
                .optionText(option.getOptionText())
                .orderIndex(option.getOrderIndex())
                // isCorrect is hidden for participants
                .build();
    }

    /**
     * Shuffle a list
     */
    public <T> List<T> shuffleList(List<T> list) {
        if (list == null || list.isEmpty()) {
            return list;
        }
        List<T> shuffled = new java.util.ArrayList<>(list);
        Collections.shuffle(shuffled);
        return shuffled;
    }
}
